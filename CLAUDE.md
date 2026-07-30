# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Arkon Essentials** (mod id `arkonessentials`, package `com.maxazarcon.arkonessentials`) — a Fabric
server-side mod for Minecraft 26.2. Admin, build and god-tier modes with separate loadouts, vanish,
flight, teleports and homes, plus a public `/tps`.

The client jar is optional and adds only the HUD indicator. The server never requires it.

**It was called "Admin Mode" through 0.10.x.** Admin mode is now one *feature* of the mod, which is
why `AdminState`/`AdminManager`/`AdminCommand` keep their names while the identity classes are
`ArkonEssentials` and `EssentialsData`. The rename moved the saved-data path, so `EssentialsData.get`
reads the legacy `adminmode:admin_mode` storage key when nothing exists under the current one and
rewrites it under the new key (verified end-to-end on a dev server). The old file is left in place
rather than deleted — free rollback. Do not remove `LEGACY_TYPE` until you are certain no
pre-0.11 world will ever be opened again.

## Read this before writing any 26.2 code

Minecraft 26.2 released June 2026 and is **past the training cutoff of most models**. Large parts of
the API that older knowledge describes are wrong. Assume nothing; the notes below were each verified
against decompiled sources, and several cost a failed build to discover.

### Toolchain (verified, do not "modernise")

| | |
|---|---|
| Minecraft | `26.2` — year.drop.hotfix scheme. There is no 1.22; 1.21.x was followed by 26.1. |
| Java | **25**, required since 26.1 |
| Loom / Gradle | 1.17 / 9.5.1 |
| Fabric Loader / API | 0.19.3 / `0.155.2+26.2` |
| Mappings | **Mojang official.** Yarn was deprecated at 26.1. |

Two build-script consequences that look like mistakes but are correct:

- **There is no `mappings` line** in `build.gradle`. Loom 1.17 uses Mojang mappings implicitly.
  Adding `loom.officialMojangMappings()` is wrong.
- Mod dependencies use **`implementation`**, not `modImplementation`.

### Renames and API changes that break older assumptions

- `ResourceLocation` is now `net.minecraft.resources.Identifier`. There is **no `Identifier.of`** —
  use `Identifier.fromNamespaceAndPath(ns, path)` or `Identifier.withDefaultNamespace(path)`.
- **Integer op levels are gone.** `CommandSourceStack.hasPermission(int)` does not exist. The system
  is now `Permission` / `PermissionCheck` / `PermissionSet` in `net.minecraft.server.permissions`.
  Sources expose `permissions()` (via `PermissionSetSupplier`), and vanilla commands gate with
  `.requires(Commands.hasPermission(new PermissionCheck.Require(...)))`.
- `ServerPlayer` has **no `getServer()`**. Use `player.level().getServer()` (nullable). Also on
  `ServerPlayer`: `permissions()`, `gameMode()`, `setGameMode(GameType)`, `createCommandSourceStack()`.
- `DimensionDataStorage` is now `SavedDataStorage`, and `MinecraftServer.getDataStorage()` returns a
  **server-global** store, separate from per-level data. `SavedDataType` takes **four** args:
  `(Identifier, Supplier<T>, Codec<T>, DataFixTypes)`. `SavedData` itself is now just a dirty flag —
  serialization is entirely via the codec.
- `PayloadTypeRegistry.playS2C()` is now **`clientboundPlay()`** (and `serverboundPlay()`).
- **HUD rendering was refactored for the Vulkan backend.** `HudElement` is
  `extractRenderState(GuiGraphicsExtractor, DeltaTracker)` — *not* the old immediate-mode
  `render(GuiGraphics, ...)`. Draw text with
  `graphics.text(Font, Component, int x, int y, int argb, boolean shadow)`.
- Player join/leave chat messages live in **two different classes**: join in
  `PlayerList#placeNewPlayer`, leave in `ServerGamePacketListenerImpl#removePlayerFromWorld`.
- `Inventory.getContainerSize()` spans the main inventory **and** equipment slots, so one index walk
  over `getItem`/`setItem` covers armour and offhand.

### The permission trap

`LevelBasedPermissionSet.hasPermission` returns **`false` for any mod-defined `Permission.Atom`,
regardless of op level**. A node-only check therefore locks out even the server owner on a server
with no permissions mod. `AdminPermissions.check` deliberately ORs a level check with the named node:

```java
permissions.hasPermission(GAMEMASTER) || permissions.hasPermission(node)
```

Do not "simplify" this to the node alone.

### Permission tiers

**Nothing implies anything else.** Groups and inheritance belong in the permissions mod, where an Admin
group lists what it grants or uses a wildcard like `arkonessentials.admin.*`. The mod deliberately does
*not* hardcode "admin confers ghost" — that would stop an operator composing a group that excludes
something. The single exception is operator level, below.

Everything routes through **Fabric's permission API**, not vanilla's `PermissionSet`, so a permissions
mod only has to implement one thing. Three shapes, from `PermissionContextOwner`:

| Call | Meaning | Used for |
|---|---|---|
| `check(source, node)` → `checkPermission(node, PermissionLevel.GAMEMASTERS)` | granted, else operator | staff nodes |
| `checkPublic(source, node)` → `checkPermission(node, true)` | allowed unless revoked | `tps`, `ping`, `home` |
| `checkPermission(typedNode, configDefault)` | a value, else config | `home.limit`, `fly.demigod` |

The namespace: `tps`, `ping`, `home`, `home.named`, `home.limit`*, `passive`, `build`, `build.nv`,
`build.reach`, `god`, `demigod`, `fly`, `fly.speed`, `fly.demigod`*, `admin.mode`, `admin.ghost`,
`admin.tp`, `admin.home`, `admin.home.limit`*, `admin.see_hidden` (* = valued).

`fly.speed` is separate from `fly` on purpose: a player in Build Mode is in creative and so already
flies natively without ever holding `fly`. Bundling them would leave builders able to fly but unable to
tune it. The `/fly` root opens for either node and the toggle re-checks `fly` itself.

**`/admin off` must stay reachable.** `mayUseAdminSuite` also returns true for anyone currently in a
state, so a player demoted while in Build Mode is not stranded in creative with no way back. Any new
gating must preserve that escape.

**Public commands must default to allowed.** Node-gating `/tps` or `/ping` with a deny default would
make them operator-only on a server with no permissions mod — the opposite of the intent. Use
`checkPublic` for anything meant to be universal.

**Verified against real LuckPerms** (v5.5.57, Fabric 26.2, installed in `run/mods/` — gitignored, so
re-download it if you want to repeat this). Findings, all confirmed on a live server:

- LuckPerms **does** implement the official `fabric-permission-api-v1`, so mod nodes resolve. Note it
  is a *different* API from lucko's older third-party `fabric-permissions-api` despite the near
  identical name — the mod targets the official one.
- Identifiers map to dotted strings: `arkonessentials:build` ⇄ `arkonessentials.build`.
- **Dotted nodes inherit from their parent.** Granting `arkonessentials.build` also resolved
  `build.nv` and would cover any future `build.*`. Explicitly setting a child to `false` overrides
  that, which is how you grant Build Mode while withholding night vision. Worth knowing before adding
  a `<parent>.<child>` node whose child should be more restrictive than its parent.
- `/arkon perms <player|uuid>` reports each node as `granted` / `denied` / `default`, where *default*
  means nothing granted or denied it and the fallback decided. It resolves offline UUIDs through
  `PermissionContext.offlinePlayer`, so a permission setup can be inspected with no second account.
  Its `Gate.fallback` predicates **must** mirror the real checks — an early version marked
  `home.named` public-by-default when it actually defaults from config, and the diagnostic
  confidently reported the opposite of the truth.

**`/arkon config` is operators only and has no node at all.** `mayEditConfig` is a bare level check
that deliberately bypasses all of the above — it changes server-wide behaviour for everyone, so no
staff grant should reach it.

The intended tiers: `passive` alone (a safe mode for a young player), `build` alone (build tools, no
invisibility), `ghost` alone (moderation without handing out creative), `admin` (everything).

**Two traps this arrangement has:**

1. **Op level satisfies every check.** The `|| GAMEMASTER` fallback has to exist — vanilla answers
   `false` to mod-defined permissions at any op level, so without it a lone server owner with no
   permissions mod would be locked out entirely. The consequence is that an **opped** builder also has
   god mode. Tiered staff must be left unopped and granted nodes through a permissions mod.
2. **The `/admin` root must not require `admin`.** It gates on `mayUseAdminSuite` — *any* suite node —
   because requiring `admin` would put every subcommand out of reach of exactly the people the
   individual nodes exist for. Bare `/admin` therefore checks its own node inside `toggleAdminMode`
   rather than relying on the root. Every mode also has a root alias (`/passive`, `/build`, `/ghost`,
   `/godmode`, `/demigod`) so a single-node holder never depends on the `/admin` tree at all.

### Getting ground truth

The decompiled Mojang-mapped sources are the authority. Extract them and read the real signatures
rather than guessing:

```bash
./gradlew genSources    # already cached after the first run
```

Then unzip `.gradle/loom-cache/minecraftMaven/**/minecraft-merged-*-26.2-sources.jar` somewhere
temporary and grep it. This is faster and far more reliable than searching the web for 26.2 APIs.

### `/tps` — public tick readout

Vanilla already reports TPS/MSPT via `/tick query`, but the whole `/tick` tree sits behind
`Commands.LEVEL_ADMINS` (op 3), so players cannot use it. `TpsCommand` exposes just the readings with
no `.requires` at all.

**TPS is not `1e9 / averageTickTime`.** The server targets `tickRateManager().tickrate()` (default 20,
changeable via `/tick rate`) and sleeps out the remainder, so the naive formula reports ~3300 TPS on an
idle server. The correct value is `min(target, 1e9 / getAverageTickTimeNanos())` — which is also how
vanilla decides "lagging". Read the target from the manager; never hardcode 20.

### Config (`config/arkonessentials.json`)

`EssentialsConfig` is a plain Gson-mapped class with initialised fields, **not** a record — Gson leaves
a field untouched when its key is absent, so an old config file picks up defaults for new keys instead
of nulling them. A file that fails to parse is left strictly alone and defaults apply for the run; same
reasoning as the saved-data guard, and for the same reason: never destroy an operator's hand-edited
file over a stray comma.

Keys: `playerHomes`, `playerNamedHomes`, `adminHomes`, `defaultBuildReach`, `buildNightVisionAvailable`,
`defaultFlySpeed`. Loaded first thing in `onInitialize`, before anything reads a default.

**Editable in game** via `/arkon config <key> [value]` (`/arkon reload` re-reads a hand-edited file).
The command subtree is *generated* from `EssentialsConfig.OPTIONS`, so declaring a new `Option` there is
the only step needed to make it editable — do not hand-write command nodes for settings. Each option
carries its own `ArgumentType`, so bad input is refused at parse time with a caret rather than clamped
afterwards.

`ArkonCommand.applyToOnlinePlayers` is what makes an edit take effect without a relog, and it exists
for two reasons that are easy to miss:

1. Values already **pushed onto** a player — Build Mode's transient reach modifier and
   `abilities.flyingSpeed` — were computed when applied, so they must be recomputed.
2. **`.requires` results are baked into the per-client command tree** when it is sent. A setting that
   gates a command (`playerNamedHomes`, `buildNightVisionAvailable`) leaves clients showing a stale
   tree unless `server.getCommands().sendCommands(player)` is called for each. Verified: flipping
   `playerNamedHomes` grows `/home` from `[set]` to the full named suite with no reconnect.

**Numeric permissions override the config per player.** Vanilla's `PermissionSet` is boolean-only, but
Fabric's permission API carries typed values: `PermissionNode.ofInteger(...)` plus
`PermissionContextOwner.checkPermission(node, default)` — and `CommandSourceStack` implements that
interface by injection. So `arkonessentials:home.limit` and `arkonessentials:admin.home.limit` are
Integer nodes and `arkonessentials:home.named` is a Boolean one; each falls back to its config value
when unset. With no permission provider installed nothing resolves, so the config alone governs — which
is the correct degradation, not a bug. Read them through `AdminPermissions.playerHomeLimit` /
`adminHomeLimit` / `mayNamePlayerHomes` rather than touching the nodes directly.

**The config defaults are live.** `reach_bonus`, `fly_speed` and `build_night_vision` persist as
*absent-until-set* (`Optional`) rather than carrying a baked default, so editing a config default moves
every player who never chose their own. A codec default could not do this — it is captured at class-init,
before the config file is even read. Keep new preference fields absent-until-set for the same reason.

`buildNightVisionAvailable: false` turns `/build nv` into a granted privilege: the command additionally
requires `arkonessentials:build_nv`.

### AFK (`/afk [reason]`, in-memory, not a state)

**AFK is a flag, not an `AdminState`** — same reasoning as flight. The states are mutually exclusive
and anyone can go AFK from any of them; an admin stepping away is still an admin. It is also
**memory-only** (`AfkManager.SESSIONS`, cleared on disconnect): AFK describes what someone is doing
right now, so a reconnecting player is by definition back at their keyboard.

Effects are folded into the two existing chokepoints rather than new mixins —
`AdminManager.ignoredByMobs` (used by `LivingEntityMixin#canBeSeenByAnyone`) and
`AdminManager.hungerFrozen` (used by `PlayerMixin#causeFoodExhaustion`). Both now OR the state test
with the AFK flag, so the mixins ask one question instead of knowing about two systems.

**The activity detection is the whole problem, and it is not obvious.** The server never sees
keystrokes or mouse motion — only packets. Two sources are combined:

- `ServerPlayer#getLastActionTime()`, which vanilla already maintains for `player-idle-timeout`. It is
  reset by movement keys, actual movement, attacking, interacting, item use, container clicks, chat
  and commands.
- **A per-tick head-rotation comparison, because vanilla's timer ignores looking around.** Verified in
  26.2 sources: `handlePlayerKnownMovement` resets only when `movement.lengthSqr() > 1.0E-5` —
  positional movement — and `handleMovePlayer` applies rotation via `absSnapRotationTo` *without*
  touching `lastActionTime`. Rotation is how mouse movement reaches the server, so without this check
  a player could look around all day and still be called idle. Do not "simplify" this to
  `getLastActionTime()` alone.

Sampled every tick, not on a slower schedule, so a rotation change cannot slip between two samples.
It is one float comparison per player. What it still cannot see is a key producing no packet at all
(perspective toggle, opening the inventory without clicking) — in practice the mouse moves first.

`ENGAGE_GRACE_MILLIS` (2s) exists because without it `/afk` would be unusable: letting go of the mouse
nudges it, that nudge is a rotation change, and the player would be back before standing up. Activity
only counts once it happens later than `engagedAt + grace`, which also discards the command's own
packets.

**Commands must not count as activity; chat must.** Vanilla resets `lastActionTime` for both, from the
same `ServerGamePacketListenerImpl#tryHandleChat` — one funnel, distinguished only by its `isCommand`
parameter. So AFK cannot simply read vanilla's timer: `/afk` would cancel itself, and an AFK player
running `/tps` would silently return. The mixin injects at **TAIL** of `tryHandleChat` (not HEAD —
vanilla's reset happens *inside* that method, so a HEAD hook records an instant fractionally before the
timer's new value and the comparison lets the command through) and records `lastCommandMillis`.
`AfkManager` then keeps its own `lastActivityMillis` high-water mark, adopting vanilla's value only when
it is strictly newer than the last command. The high-water mark matters: without it, a command would
erase the memory of genuine activity that preceded it.

**Two things suppress the automatic timer** and both are checks inside `tick`, not extra state: a player
appearing offline via `/fakeleave` (marking them AFK would broadcast the name their fake departure is
hiding), and `afk_enabled` being false via `/afkoff`. Manual `/afk` bypasses both.

**AFK announcements are suppressed for vanished players** (`hiddenFromPlayers()`), who are told
privately instead. Vanish already suppresses join and leave messages; broadcasting "X has gone AFK"
would undo that by naming someone nobody can see.

Config: `afkTimeoutSeconds` (90, 0 disables the timer), `afkMessage`, `afkReasonMessage`,
`afkReturnMessage`, `afkReasonsAvailable`. The messages are `%s`-templated and filled by
`AfkManager.format`, which splits on the placeholder and appends **styled components** — `String.format`
would flatten everything to one colour, and the point is that the reason is a different one. Surplus
placeholders are left as literal text rather than throwing.

`Option.ofString` uses `greedyString()`, so a message can contain spaces without quoting. Verified in
game: setting `afkMessage` to a value containing spaces, a `%s` and punctuation round-tripped intact.

Nodes: `afk` (public) and `afk.reason` (defaults from `afkReasonsAvailable`). Note the dotted-node
inheritance trap — explicitly granting `arkonessentials.afk` in LuckPerms also grants `afk.reason`
unless the child is explicitly denied. `afk` being public means nobody normally needs to grant it.

### `/tp`, `/back`, and taking the `/tp` name from vanilla

Vanilla registers `/teleport` and then `/tp` as a **redirect alias**, both at `LEVEL_GAMEMASTERS`.
Registering our own `/tp` does **not** override it: Brigadier's `CommandNode.addChild` *merges* literals
sharing a name and **keeps the existing node's requirement**, so our subcommands would silently inherit
the op-2 gate. `TeleportCommandMixin` therefore `@Redirect`s the **second** of the two
`dispatcher.register` calls in `TeleportCommand#register` (ordinal 1 — the first builds `/teleport`
itself) and returns null; the result is discarded at the call site. `/teleport` is untouched, so every
vanilla form — selectors, `facing`, explicit rotation — is still there.

**Argument order in `/tp` is load-bearing.** Brigadier tries argument children in insertion order and
commits to the first that parses. `EntityArgument.player()` accepts any bare word, so with the player
branch first, `/tp 100 -200` parsed as *two player names* and failed with "No player was found" instead
of reaching the column form — caught only by running it. The coordinate branches (`Vec3Argument`, then
`Vec2Argument`) are registered **before** the player branch; numbers cannot masquerade the other way, so
the order is unambiguous. Verified live: `/tp 100 -200`, `/tp 1 2 3`, `/tp Steve` and `/tp Steve Bob`
each route to the intended branch.

`/tp <x> <z>` picks its own height from `Heightmap.Types.MOTION_BLOCKING_NO_LEAVES` — the same map the
game uses for lightning and spawns, so it lands on a surface that holds a player rather than on treetops.

**`/top` deliberately does not use a heightmap**, because the two things that make it non-trivial are
exactly what a heightmap ignores. It walks the column down from `getMaxY()` (inclusive) and takes the
first block that `blocksMotion()`, **skipping bedrock** — the Nether ceiling is bedrock with open air
above it, so the naive answer is "on the roof" — and then requires the space above to pass
`level.noCollision(player, box)` using the player's **real bounding box**, so partial shapes are judged
by whether they actually collide. That headroom test is also what stops the search settling in the
solid rock beneath the Nether roof. Lava is rejected separately: it does not block motion, so the
collision test alone would happily drop someone into a lava lake sitting on terrain.

There is also a **README.md** at the repo root documenting commands, permissions and config for
operators. It is user-facing where this file is not — keep it in step when the command surface or the
node list changes.

**`back_point` is separate from `return_point` on purpose.** `return_point` belongs to the admin
ping-pong and is rewritten by `/admin tp`, which would otherwise stamp over a player's own last position
every time staff teleported. One field rather than two (teleport-origin *and* death) so "most recent
wins" needs no timestamps — whichever event wrote last is simply the one stored. Death only writes it
when the player holds `tp.death`, checked at the moment of death via `ServerLivingEntityEvents.AFTER_DEATH`.

Nodes are deliberately **siblings**: `tp`, `tp.others`, `tp.coords`, `tp.back`, `tp.death`, `tp.top`,
`tp.here`, `tp.there`, `tp.all`. Nesting death return under `tp.back` would hand it out with every
`/back` grant, since a dotted child inherits from its parent — a child cannot be stricter than its
parent.

### Immunities invert the permission model

`tp.immune` and `admin.grant.immune` are checked with **`AdminPermissions.checkStrict`**, which is
`checkPermission(node, false)` — no operator fallback. This is the one place the usual "granted, or else
operator" rule must not apply. An immunity is a *protection*, not a capability: with the normal fallback
every operator would be immune to every other operator, and on a server with no permissions mod the
entire staff would be untouchable by each other, which is the opposite of the intent.

Their `Gate` entries use the `DENIED` predicate for the same reason — reporting them as `OPERATOR` would
make `/arkon perms` confidently disagree with the real check, the exact failure the gate table exists to
prevent. Neither immunity applies to yourself, so holding one never blocks your own commands.

`admin.grant.<mode>` nodes are **generated from `AdminState`** in both `grantCommand()` and
`allGates()`, so adding a state cannot leave a hole in either the command tree or the diagnostic.
`admin.grant.immune` shares that prefix, so a wildcard `admin.grant.*` confers immunity too — usually
desirable for senior staff, but worth knowing.

### `/fakeleave` and `/fakejoin`

Announcements are built from vanilla's own keys — `multiplayer.player.left` / `joined` with
`player.getDisplayName()` and `ChatFormatting.YELLOW` — not from hand-written strings. Anything a
resource pack, language setting or chat mod rewords is reworded here too, so the fake is
indistinguishable rather than merely similar to the English default.

`/fakeleave [mode]` accepts **every** state, concealing or not, including `none` — announcing a departure
while standing in plain sight is a deliberate use, not a mistake to guard against. (An early version
restricted it to `hiddenFromPlayers()` states; that was wrong and was opened up.) `NONE` has an empty
label, so the confirmation message has separate wording rather than a sentence with a hole in it.
Default from `fakeLeaveDefaultMode` (ghost); unknown values fall back to Ghost.

The appearing-offline flag drives the HUD label and is **independent of the state**, which is what lets
every combination read correctly — `none` + the flag shows just `Offline Mode` with no mode above it.

`/fakejoin` broadcasts and **clears the appearing-offline flag but does not touch the player's state** —
coming off duty stays a separate, explicit act. The flag is memory-only like AFK.

### HUD payload and version negotiation

`AdminStatePayload` carries four values: state, `flightActive`, `afk`, `appearingOffline`. The last
three are flags, not states, so any combination can be on and the indicator block simply grows —
`Offline Mode`, then `AFK`, then `Flight Enabled`, under the state label.

**The channel name carries the protocol version, and that *is* the negotiation.** The type is
`arkonessentials:state_v<PROTOCOL_VERSION>`. A client only receives packets on channels it registered a
receiver for, and `ServerPlayNetworking.canSend` reports exactly that — so a client built against a
different shape has registered a different channel, is never sent one, and cannot misread it. The
failure mode is a blank indicator, never a desync or a disconnect.

So **changing the record's fields means bumping `ArkonEssentials.PROTOCOL_VERSION`**, which makes the
incompatibility structural rather than something both sides must remember. It is independent of the mod
version; cosmetic and server-side changes do not touch it.

`HandshakePayload` (C2S, `arkonessentials:handshake`) is what turns that silent degradation into an
explanation: the client sends its protocol and mod version on join, and a server that disagrees logs it
and tells the player in chat, naming both versions. **That channel's shape must never change** — it is
the one thing both sides must agree on before they can discover they disagree about anything else.
Anything new belongs in its own payload. The warning is sent as plain chat, not a payload, so it arrives
whatever the client can decode.

Clients older than 0.24 send no handshake and registered the unversioned `state` channel, so they
degrade safely but silently — nothing can be said to them.

**The `Entry` codec group was restructured at 0.26.0 and now sits at 8 of DFU's 16.** Fields are grouped
into `Preferences`, `Locations` and `Legacy`, each a **`RecordCodecBuilder.mapCodec`** — which writes its
keys at the *parent's* level rather than nesting them. So the group costs one argument instead of many
while the on-disk layout is unchanged: no migration, no `DATA_VERSION` bump, no downgrade risk. Vanilla
does the same thing (`BlockPredicate` embeds `DataComponentMatchers.CODEC` this way).

**Verified byte-identical**, not assumed: the encoded NBT was captured before and after the restructure
and diffed, matching exactly once the test's random UUID was normalised out. If you restructure further,
repeat that — temporarily dump `encode(data).toString()` from the round-trip test and compare.

Two maintenance wins worth preserving: `Preferences.isDefault()` is `equals(DEFAULT)`, so a new field
cannot be forgotten there (it used to be a hand-written clause the compiler could not check), and `Entry`
keeps **delegating accessors** (`reachBonus()`, `homes()`, …) so grouping stayed a change to one file
rather than every call site.

### Client config (`config/arkonessentials-client.json`) and the Mod Menu screen

`HudConfig` (client source set) is a **second, separate** config, and the split is deliberate: the
server one is operator-owned and governs what players may *do*; this one is a single player's
preference about their own screen. Nothing in it can change permissions or behaviour, so it needs no
node, never syncs, and no server ever reads it. Do not merge them.

Same plain-class-with-defaults shape and the same leave-an-unparseable-file-alone rule. Two wrinkles
Gson forces:

- Any nested object needs a **declared no-arg constructor** (`Indicator()`), or Gson allocates around
  the field initialisers and hands back nulls. `normalise()` also null-checks every nested field,
  because a key that is *present and null* defeats the initialiser regardless.
- Indicator colours start `null`, meaning "whatever `AdminState` says", so an untouched file follows a
  future change to a built-in colour instead of pinning today's value. `load()` writes the resolved
  colour back so the file is still self-documenting. Unlike a whole unreadable file, one bad colour
  *is* repaired in place — it cannot cost more than a colour.

`HudConfig.slots()` is the single definition of the indicator set; the screen and `normalise()` both
iterate it, so adding a state is one line there rather than three places.

**The screen uses vanilla's own widgets** — `OptionsSubScreen` + `OptionsList` + `OptionInstance`
(`createBoolean`, `IntRange` sliders, `Enum` cycle buttons), plus a raw `EditBox` for hex entry via
`OptionsList.addSmall(AbstractWidget, AbstractWidget)`. **No Cloth Config**, deliberately: it would be
a hard dependency every player had to install, against the point of a mod whose client half is
optional. Notes for anyone extending it:

- `OptionsList`'s constructor demands an `OptionsSubScreen`, so the screen must subclass it rather than
  plain `Screen`.
- `removed()` is overridden **without** calling `super`, whose only job is saving vanilla's
  `options.txt` — this screen never touches those.
- Captions are built as `caption.copy().append(": " + value)` rather than through a vanilla format key,
  so a key rename upstream cannot break the labels. Real translation keys live in
  `src/client/resources/assets/arkonessentials/lang/en_us.json`.
- `OptionInstance` captions are **translation keys**, not literals.

**Mod Menu is optional and must stay that way.** `compileOnly` in `build.gradle` (plain `compileOnly`,
*not* `modCompileOnly` — consistent with `fabric-api`, since the 26.2 ecosystem publishes against
official mappings and needs no Loom remap), `"suggests"` in `fabric.mod.json`, and the `modmenu`
entrypoint that only Mod Menu itself ever asks for. Verified: a dedicated server with no Mod Menu
installed boots clean (`Done (0.541s)`, no entrypoint complaint).

## The repo and releases

`https://github.com/arkon-interactive/Arkon-Essentials`, branch `main`. Git identity and credentials are
already configured locally (Git Credential Manager), so `git push` works without anyone handling a token.
**The `gh` CLI is not installed**, which is why nothing here depends on it.

Two workflows in `.github/workflows/`:

- **`build.yml`** — every push to `main` and every PR. Its real value is running on a *clean checkout*:
  it catches a file that was never committed, or one that only builds because of local Gradle state.
- **`release.yml`** — triggered by pushing a `v*` tag. It builds and publishes the jars using the
  runner's built-in `GITHUB_TOKEN`, so releases need no personal access token anywhere.

**Cutting a release is two commands**, after `mod_version` in `gradle.properties` is already correct:

```bash
git tag v0.26.0 && git push origin v0.26.0
```

The release job **refuses to publish if the tag disagrees with `mod_version`**. Without that check a
mismatched tag would ship a jar whose own version string is wrong — invisible until someone reports a bug
against a version that was never built.

Two things that would break CI and are easy to reintroduce:

- **`gradlew` must stay mode `100755`.** Committed from Windows it lands as `100644` and the Linux runner
  fails with "Permission denied". Fixed once with `git update-index --chmod=+x gradlew`; check it survives
  if the wrapper is ever regenerated.
- **`run/` must stay ignored.** It holds the dev world, logs and any test mods (LuckPerms), and is large.

## Versioning and save-data safety

The mod is **pre-1.0 on purpose** and stays there until the feature set settles: `0.y.z`, where `y`
absorbs breaking changes and `z` is fixes. Do not advance to `1.0.0` unasked — that number promises the
commands, permission nodes and save format are stable, and they are not yet. (An early build was
briefly numbered `1.0.0`/`1.1.0`; it was renumbered to `0.2.0`.)

`EssentialsData.DATA_VERSION` is **independent of the mod version** and tracks only the saved-file
schema. Bump it when an older build reading the file would lose something a player would care about —
inventories, saved locations — and add a migration in `Entry.fromDisk` rather than silently dropping
the old field; the legacy `admin_inventory` fold is the worked example.

Do **not** bump it for additive settings that have a sane default, such as `reach_bonus`. An older
build silently resetting a preference is a far cheaper failure than the guard disabling the mod
outright, and the guard is a blunt instrument — reserve it for data that cannot be retyped in one
command.

**The downgrade guard.** If the file's `data_version` exceeds `DATA_VERSION`, the instance is loaded
empty and marked locked: `setDirty` is overridden to a no-op, `put` refuses, and every command reports
why. This is deliberately not an exception, because of how `SavedDataStorage` behaves:

```java
return type.codec().parse(ops, tag.get("data"))
    .resultOrPartial(error -> LOGGER.error(...))
    .orElse(null);
```

A parse failure yields `null`, `computeIfAbsent` then builds a fresh instance, and `set()` marks it
dirty immediately — so **failing to read the file schedules an overwrite of it**. Staying clean is the
only thing that keeps the newer file intact. Two rules follow, and future format changes must respect
both: `data_version` stays a plain int at the root, and `players` stays an optional list. The guard can
only report a version it is still able to read.

`ArkonEssentials` also touches the data on `SERVER_STARTED` so the check runs while an operator is watching
the log, rather than on the first command hours later.

## Commands

`JAVA_HOME` must be set explicitly — the JDK is installed but is **not on PATH**:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.4.7-hotspot"
```

Adoptium updates in place and leaves the old directory behind as an empty shell, so the patch version
here drifts. If a build fails with *"JAVA_HOME is set to an invalid directory"*, list
`C:\Program Files\Eclipse Adoptium\` and take the one that still has `bin\java.exe`. Setting the
variable is not enough on its own — **a running Gradle daemon keeps the JDK it was started with**, and
reports the *old* path even after you export the new one. `./gradlew --stop` first.

```bash
./gradlew build                 # compile + jar → build/libs/arkonessentials-0.11.0.jar
./gradlew genSources            # decompile Minecraft (slow, cached)
./gradlew -p "$(pwd)" runServer # dev server; -p guards against working-directory drift
```

```bash
./gradlew test                  # codec round-trip tests (also run by plain `build`)
```

### Codec tests

`EssentialsDataTest` round-trips the whole persistence surface through the real codecs under
`fabric-loader-junit` (version kept in lockstep with the loader). It exists because a decode failure
is *silent data loss* — `SavedDataStorage` swallows it and schedules an overwrite — and because a
field-order slip between `return_point` and `home` (same type, adjacent in the codec group) compiles
cleanly. Any schema change should extend this test, not skip it.

**26.x trap, learned the hard way:** `Bootstrap.bootStrap()` no longer makes `ItemStack`s usable.
Item default components are bound in a second phase — registration only queues initializers, and a
real server applies them during resource load. In a test JVM you must do it manually or every
ItemStack constructor dies with "Components not bound yet":

```java
HolderLookup.Provider registries = VanillaRegistries.createLookup();
BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(PendingComponents::apply);
```

Use `VanillaRegistries.createLookup()` (not static registries alone) — vanilla hands the initializers
the full dynamic-registry context, and the same provider then serves as the `RegistryOps` for
item codecs.

Mixin application is additionally checked at runtime — see below.

### Verifying mixins actually apply

**Compiling proves nothing about mixins.** They are matched by method descriptor at class load, so a
wrong `@Redirect` target or `@Shadow` signature fails only when the target class is first loaded.
After any mixin change, boot the dev server and check:

```bash
grep -E "Mixing [A-Za-z]+ from ArkonEssentials" run/logs/debug.log
grep -iE "InjectionError|Critical injection" run/logs/debug.log
```

All five mixins must appear. Two gotchas:

- `ChunkMap$TrackedEntity` is an inner class that only loads once an entity **enters tracking**. On an
  empty server it never loads. To force it: set `pause-when-empty-seconds=0` in
  `run/server.properties`, then `forceload add 0 0` followed by `summon minecraft:pig 8 100 8`.
- The server has no usable stdin here. Drive it by enabling RCON in `run/server.properties` and
  sending commands over the RCON protocol. Revert `enable-rcon` / `rcon.password` / `online-mode`
  afterwards.

## Architecture

Five **mutually exclusive** states (`AdminState`), which is what lets the HUD use a single indicator:

| State | Hidden from players | Ignored by mobs | Game mode | Inventory |
|---|---|---|---|---|
| `NONE` | — | — | — | — |
| `ADMIN` | yes | yes | creative | admin loadout |
| `PASSIVE` | — | yes | untouched | untouched |
| `BUILD` | — | — | creative | build loadout |
| `GOD` | — | — | untouched | untouched |
| `DEMIGOD` | — | — | untouched | untouched |
| `GHOST` | yes | yes | untouched | untouched |
| `VANISH` | yes | yes | untouched | admin loadout |

`GHOST` is `GOD` plus fully vanished: untouchable and unseen while still in survival holding your own
gear, which is the one thing `ADMIN` cannot give you (it forces creative). It exists precisely so the
mutually-exclusive state model does not have to be broken into stackable flags.

**There is no HIDDEN state.** It existed through 0.9.x (vanish without protection) and was removed as
redundant with `GHOST` — its mortality even leaked surveillance via death messages. `AdminState.CODEC`
folds the legacy `"hidden"` save value into `GHOST` (tested); never reuse `"hidden"` as a serialized
name, and do not re-add the state without the death-message problem solved. The `ArkonEssentials:hide`
permission node is gone too; `ArkonEssentials:see_hidden` remains (it governs ops seeing ADMIN/GHOST).

`GOD`, `DEMIGOD` and `GHOST` layer protection over whatever the player was already doing rather than
replacing it. Both pin health and hunger full and spare carried gear from durability loss. They differ in *where*
they intervene: `GOD` refuses the hit outright (`ServerLivingEntityEvents.ALLOW_DAMAGE`, plus
`knockback` and `canBeAffected`), while `DEMIGOD` lets the entire damage pipeline run — animation,
knockback, particles — and only refuses the health drop, via a `setHealth` mixin that blocks decreases
but not rises. `ALLOW_DEATH` backstops both, since a source that kills outright rather than by
subtraction would otherwise slip past a health guard.

`BUILD` additionally grants infinite night vision and a per-player reach bonus on both
interaction-range attributes, set with `/build reach <0-10>` and defaulting to `+4`. It is deliberately
**not** hidden: creative already makes a player invulnerable so hostile mobs ignore them, and staying
visible is the point when building in front of people.

`/build` is the same Brigadier subtree as `/admin build`, registered twice via `buildCommand(name)`
rather than redirected — each call must return a fresh builder, since reusing one shares nodes between
the two trees.

`ADMIN` is `HIDDEN` plus creative. Moving between those two only swaps the inventory and game mode —
`refreshVisibility` fires only when `hiddenFromPlayers()` actually changes, so the player never
flickers back into view.

**`AdminManager`** owns every transition; nothing else mutates state. **`EssentialsData`** is the
global `SavedData` and persists, per player: current state, `survivalInventory` (held only while in a
creative state), a **map of loadouts keyed by state** (kept permanently so staff never restock),
`lastNonCreativeMode`, and `returnPoint`. Persisting to disk rather than memory is deliberate — a
crash mid-admin-mode would otherwise destroy a player's inventory.

Any state where `stashesInventory()` is true gets its own loadout, so admin tools and building tools
never mix and neither touches the player's own gear. `setState` is written around that: it saves the
outgoing state's loadout always, but only stashes survival gear when coming **from** a non-creative
state — otherwise switching `ADMIN` → `BUILD` would overwrite the survival stash with a creative one.

Teleport lives alongside the states rather than inside them. Both destinations are a `SavedLocation`
(dimension, position, rotation) but they behave differently on purpose:

- `/admin tp <player>` (aliased `/atp`) saves the **return point**, forces `ADMIN`, then teleports.
  `/admin back` **swaps** — it sends the player to the saved point and stores where they just were, so
  repeating it ping-pongs between two locations.
- `/admin home set` pins a **home**, and `/admin home` is a plain teleport to it. Home is fixed:
  going there changes neither the player's state nor the return point.

The five mixins each cover one leak:

| Mixin | Purpose |
|---|---|
| `LivingEntityMixin` | `canBeSeenByAnyone()` → false. Single chokepoint for mob targeting: `TargetingConditions#test` checks it first and `canBeSeenAsEnemy` delegates to it. |
| `TrackedEntityMixin` | Cancels `updatePlayer` so the entity is never sent to disallowed clients. This is what vanish actually *is*. |
| `ListPlayersCommandMixin` | Filters `/list` — redirects `getPlayers()` inside `format`, the single source of both the names and the count |
| `MinecraftServerMixin` | Filters the server-list ping — redirects `getPlayers()` inside `buildPlayerStatus`, feeding both the count and the sample |
| `PlayerListMixin` | Suppresses the join message |
| `ServerGamePacketListenerImplMixin` | Suppresses the leave message |
| `ServerPlayerMixin` | Records `lastNonCreativeMode` on every `setGameMode` |
| `PlayerMixin` | Cancels `causeFoodExhaustion` — the single source of hunger loss, so the bar freezes without per-tick writes |
| `ItemStackMixin` | Cancels the four-arg `hurtAndBreak`, the funnel every other overload delegates into, sparing armour and tools |
| `ServerPlayerGameModeMixin` | TAIL of `setGameModeForPlayer` — re-asserts `/fly` and books soft landings; see Flight below |

Tab list and locator bar are handled without mixins — by pushing packets from `refreshVisibility` and
calling `ServerWaypointManager.track/untrackWaypoint`.

**What vanish does and does not reach.** Because it works at the tracker, a client is never told the
player exists — so **client-side minimaps (JourneyMap, Xaero's, VoxelMap) show nothing**, which an
invisibility effect would not achieve (an invisible entity is still an entity the radar can draw).
Anything the player *does* (chat, block changes, container use) is still visible; vanish hides presence,
not actions.

**Web maps and the ping read `PlayerList` directly** and never touch the client packet path, so each
needed closing separately:

- `MinecraftServerMixin` redirects the single `getPlayers()` call in `buildPlayerStatus`, which feeds
  both the ping count *and* the name sample — filtering one and not the other would leave them
  contradicting each other. Gated on `hideFromPing`.
- `WebMapIntegration` handles **BlueMap** (`BlueMapAPI.getInstance().getWebApp().setPlayerVisibility`)
  and **squaremap** (`SquaremapProvider.get().playerManager().hidden(uuid, hidden)` — note its API is
  inverted relative to everything else here). Gated on `hideFromWebMaps`.

Both API artifacts are `compileOnly` (`de.bluecolored.bluemap:BlueMapAPI`, `xyz.jpenilla:squaremap-api`)
and every call sits behind `FabricLoader.isModLoaded`, so a server without them never loads the classes.
`refresh` **computes** visibility rather than being told it, so a caller cannot get the direction wrong,
and turning `hideFromWebMaps` off *restores* visibility rather than merely ceasing to hide — otherwise a
player hidden before the edit would be stuck on the map.

Call it from anywhere concealment can change: `refreshVisibility`, `PresenceManager.fakeLeave/fakeJoin`
(needed on its own, since `/fakeleave none` never triggers `refreshVisibility`), `onJoin`, and
`ArkonCommand.applyToOnlinePlayers`.

**Dynmap is deliberately absent** — no Fabric build for 26.2, so there is nothing to test against.

**Verified** on the dev server with BlueMap 5.22 and squaremap 1.3.15 in `run/mods/`: both detected at
startup, both mixins applied, no injection errors, squaremap's `/tiles/players.json` live, and the status
ping still answers (`scratchpad/ping.py` is a minimal status-ping client — status only, so it needs no
login). **The filtering itself is unproven**: with nobody online there is no vanished player to omit, so
that last step needs a real client.

**`/afk` is refused while appearing offline**, and `/fakeleave` silently clears an existing AFK via
`AfkManager.clear` (no announcement). `AfkManager.announce` additionally suppresses for
`isAppearingOffline`, not just `hiddenFromPlayers` — necessary because `/fakeleave` accepts `none`, so
appearing-offline and not-hidden can be true at once.

### Flight (`/fly`, `/fly speed <1-3>`, per-player, persisted)

Flight is a **flag, not a state**, and a perk of the god-tier only: the effective grant is
`isFlightActive` = preference ∧ `AdminManager.stateGrantsFlight(player, state)`. God and Ghost always
qualify (`AdminState.alwaysGrantsFlight`); **Demigod is conditional** — off by default via the
`demigodFlight` config key, grantable per player through `arkonessentials:fly.demigod`. That is why the
check takes a player and lives in `AdminManager` rather than on the enum. A config edit can therefore
*revoke* flight, which is why `ArkonCommand` calls `refreshFlight` (grant **or** tear down, with a soft
landing) rather than `applyFlightAbilities` (grant only). Admin and Build already
fly natively through creative; Hidden and Passive deliberately do not get flight. `grantsFlight()` is
currently the same trio as `protectsPlayer()` but enumerated separately on purpose — protection and
flight are different grants and free to diverge. The `/fly` toggle refuses to *enable* outside those
states (disabling is allowed anywhere, to clear leftovers); moving to a non-flight state tears flight
down — with a soft landing only if flight was genuinely active, not merely a dormant preference — and
returning restores it. The preference itself persists, consistent with reach/nv/loadouts. `/fly
speed` is deliberately **not** gated: it must keep working for plain creative-mode players per the
original spec. Flight never touches game mode. It works by holding `abilities.mayfly` open;
engaging is the client's native double-tap-jump. Facts this design rests on (verified against
decompiled 26.2):

- `mayfly=true` natively: exempts from the floating kick (`ServerGamePacketListenerImpl`), and makes
  `Player#causeFallDamage` return false — so "no fall damage while fly is on" is zero code.
- The space=up / shift=down impulse while flying exists **only in `LocalPlayer.aiStep`** (client).
  The server never applies vertical flight motion; it accepts positions. Any future "custom flight
  physics" idea must reckon with this — server-driven velocity fights client prediction and
  rubber-bands. That is why /fly uses the native engine.
- Exactly one method wipes the flags: `ServerPlayerGameMode#setGameModeForPlayer`
  (`GameType#updatePlayerAbilities` hard-sets `mayfly=false` for non-creative). Game-mode changes,
  respawn (`restoreFrom`), dimension transfer, and join construction all flow through it — hence the
  TAIL mixin. `applyFlightAbilities` only ever **raises** `mayfly` (revoking is the one-off in
  `setFlyEnabled`), so mode-granted or other-mod-granted flight is never fought.
- `handlePlayerAbilities` is client-authoritative for `flying`, validated by
  `flying = packet.isFlying() && mayfly` — the client lands, clears flying, tells us. Do not fight it.
- `/fly speed` writes `abilities.flyingSpeed` (base 0.05F × multiplier), which creative flight reads
  too. Last-writer-wins against any other mod touching that field; vanilla never does.

**Soft landings**: losing a flight source while airborne (leaving ADMIN/BUILD/creative/spectator via
the mixin, or `/fly` off) books one free landing in `AdminManager.SOFT_LANDINGS` (in-memory Set).
Consumed by the fall-damage branch of ALLOW_DAMAGE; retired by `tickSoftLandings` once the player is
grounded/swimming/flying so a harmless landing cannot leave a ticket armed for some future fall.
Survives relog (same fall resumes); intentionally lost on full restart.

Client code lives in `src/client/java` (Loom `splitEnvironmentSourceSets()`) and does nothing but
render the indicator from synced state. **State sync is gated on
`ServerPlayNetworking.canSend`** — a vanilla client has no receiver registered and an unknown payload
would disconnect it.

The payload carries the state **and** whether flight is currently granted (`isFlightActive`, not the
raw preference); the HUD draws the state label at 20,20 and "Flight Enabled" (lime, `0xFF55FF55`)
beneath it. The
payload has no version negotiation, so **client and server jars must be the same mod version**; a
0.5.x client decoding the wider payload would desync/disconnect.

### `/vanish` and the loadout/creative split

Adding `VANISH` forced a refactor worth understanding: `stashesInventory()` used to mean *both* "uses a
loadout" and "is creative". Vanish needs the first without the second, so they are now separate —
`stashesInventory()` and **`forcesCreative()`** — and `setState` decides game mode independently of
inventory. That is what makes `ADMIN → VANISH` drop out of creative while keeping the same loadout.

`loadoutKey()` is why they share one: `VANISH` returns `ADMIN`, so tools set up on duty are already to
hand. Change that one method to give Vanish its own slot instead.

Interaction blocking lives in `InteractionGuard`, built on **Fabric's interaction events rather than
mixins** — they exist for this, and cancelling through them makes the client roll back its prediction, so
a refused break shows the block reappearing instead of vanishing and popping back. `pickupsBlocked` and
`interactionBlocked` fold the state default together with the player's persisted override, so there is
one answer and one place to change it.

**Doors, trapdoors and fence gates are exempt** from the interaction block. The door still swings and
still sounds for everyone — that is unhidden movement, not silent movement. Hiding it is a separate,
unsolved problem: the block state genuinely changes server-side, so suppressing the update packet
desyncs every *other* client rather than concealing anything. A better direction, still under discussion,
is a configurable set of blocks the vanished player is told are passable — that desyncs only the vanished
player, which is the safe direction.

`EntityMixin` refuses fire ticks for fully protected players. Damage immunity is not the same as not
burning: the flames still attach, and the fire overlay fills the watcher's own screen.

**Adding a state is protocol-breaking.** `AdminState.STREAM_CODEC` is `values()[id]`, so a client built
before the constant throws `ArrayIndexOutOfBounds` decoding it. Bump `PROTOCOL_VERSION` — version 3 is
`VANISH`. New constants still go on the **end**.

## Settled behaviour — do not change without asking

These were decided deliberately; they are not oversights.

- `/admin off` is a **full reset**: it clears state, restores gear, and pulls the player out of
  creative even when the active state never put them there. Repeating `/admin hide` or
  `/admin passive` instead drops only that effect and leaves the game mode alone.
- Spectator counts as a "non-creative" mode, so `/admin off` returns a player who was spectating back
  to spectator.
- `untrackWaypoint` removes the locator-bar marker for **everyone, ops included**. Ops can still see
  the player directly. Per-viewer filtering would need another mixin.
- **Vanished players can see each other**: `canSee` returns true when the *viewer* is also hidden, not
  only when they hold `see_hidden`. Two ghosts on the same incident being invisible to one another is
  worse than useless.
- Leaving admin mode clears the inventory if no survival snapshot exists, rather than leaving it in
  place — otherwise creative items would walk into survival.
- `/admin back` **overwrites** the return point with the player's current location every time. That
  swap is the feature, not a bug — it is what lets an admin bounce between a job and where they came
  from. Do not "fix" it into a one-shot return.
- `/admin tp` forces `ADMIN`; `/admin back` deliberately leaves the state alone, so returning does not
  silently take an admin off duty mid-job.
- The return point persists across restarts and stores the dimension, so it survives a trip to the
  Nether. `SavedLocation.teleport` returns false if that dimension has since been removed.
- **The unnamed home IS the player's respawn point** — not a copy of it. `/home` teleports to
  `getRespawnConfig()` (falling back to world spawn), and `/home set` calls `setRespawnPosition(...,
  forced = true)`. This is why "your home is your spawn until you sleep in a bed" needs no code: a bed
  moves the respawn point through vanilla and the home follows, because there is nothing separate to
  drift. Do not reintroduce a stored unnamed home.
- **Two independent home tiers**, `EssentialsData.HomeTier`: `/home` (player) and `/admin home`
  (staff), stored in separate maps so the same name can exist in both without collision. `/admin home`
  is named-only — spawn-setting belongs solely to `/home set`, so exactly one command in the mod moves
  a respawn point. Overwriting an existing name is always allowed; only new names consume a slot, and
  `delete` exists because a cap with no way to free a slot is a dead end.
- Player naming is off by default (`playerNamedHomes: false`), so out of the box a player has exactly
  one home and it is their spawn — `/home` shows no name argument at all, because the gate lives in
  `.requires` and Brigadier prunes the tree per source.
- Build Mode's reach modifier is **transient**, so it is never written into player data and cannot
  strand itself on a player if the mod is removed. The cost is that `onJoin` must re-apply it; do not
  "fix" this by switching to `addPermanentModifier`.
- Loadouts persist as a **list of pairs, not `Codec.unboundedMap`**. A map codec needs its keys to
  survive as map keys in the target format — true for NBT, but silently untrue for any ops that
  compress maps. The list has no such dependency.
- Home maps use `EssentialsData.orderedCopy` (unmodifiable `LinkedHashMap`), **never `Map.copyOf`** —
  the latter's iteration order is unspecified and randomised per JVM run, which shuffles home listings
  between restarts. The round-trip test asserts insertion order precisely to catch this.
- `clearBuildPerks` calls `removeEffect(NIGHT_VISION)` unconditionally, so leaving Build Mode also
  strips a night vision potion the player drank themselves. Accepted as a minor edge case.
- The `setHealth` mixin blocks **decreases only**. Rises must keep working or healing and the entry
  top-up would both break.
- `ALLOW_DEATH` means `/kill` does not work on a player in `GOD` or `DEMIGOD`. That is the intended
  reading of "prevents health from degrading", but it does surprise people — they must leave the state
  first.
- There is deliberately **no `/gm` alias** — it collides with the `/gm` most server suites bind to
  gamemode. `/godmode` and `/tgm` only. Do not add it back.
- `/home` and `/home set` are user-requested root aliases despite colliding with essentials-style
  suites; whichever registers last wins on such servers.
- Build Mode night vision is a per-player preference (`/build nv`, default on), persisted as
  `build_night_vision`. `setBuildNightVision` applies/removes the effect live when already in BUILD.
- `DEMIGOD` is silver (`0xC0C0C0`) and `GHOST` is grey (`0x808080`). They are adjacent on purpose but
  kept two steps apart in brightness; if a third neutral ever gets added, this trio needs rethinking
  rather than another shade.
