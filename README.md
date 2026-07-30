# Arkon Essentials

A server-side essentials mod for **Fabric / Minecraft 26.2**. Admin, build and god-tier modes with
separate loadouts, vanish, flight, teleports, homes, AFK and a public `/tps`.

The client jar is **optional** and adds only the on-screen indicator. The server never requires it.

- **Mod id:** `arkonessentials`
- **Requires:** Minecraft 26.2, Fabric Loader ≥ 0.19.3, Fabric API, Java 25
- **Optional:** [Mod Menu](https://modrinth.com/mod/modmenu) ≥ 20.0.0 (client only, for the settings screen)
- **Permissions:** any mod implementing the official `fabric-permission-api-v1` — verified against
  LuckPerms 5.5.57

---

## Install

Grab the jar from [Releases](https://github.com/arkon-interactive/Arkon-Essentials/releases) and drop it
in `mods/` on the server. That is the whole installation — every feature works with vanilla clients.

Players who also install it get the HUD indicator showing their current mode.

**Version mismatches are handled, not fatal.** The sync channel's name carries a protocol version, so a
client built against a different packet shape is simply never sent one — it cannot misread it. The
result is a blank indicator, never a desync or a disconnect, and everything else keeps working. On join
the client announces itself, and a server that disagrees tells the player so in chat, naming both
versions.

---

## Modes

Six mutually exclusive states. Only one is active at a time, which is why the HUD needs a single label.

| Mode | Command | Hidden from players | Ignored by mobs | Game mode | Inventory |
|---|---|---|---|---|---|
| **Admin** | `/admin` | yes | yes | creative | admin loadout |
| **Passive** | `/passive` | — | yes | untouched | untouched |
| **Build** | `/build` | — | — | creative | build loadout |
| **God** | `/godmode` | — | — | untouched | untouched |
| **Demigod** | `/demigod` | — | — | untouched | untouched |
| **Ghost** | `/ghost` | yes | yes | untouched | untouched |
| **Vanish** | `/vanish` | yes | yes | untouched | admin loadout |

**God vs Demigod.** God refuses the hit outright — no damage, no knockback, no harmful effects.
Demigod lets the whole damage pipeline run, so you still see the hurt animation, knockback and
particles, and only the health loss is refused. Both pin health and hunger full and spare carried gear
from durability loss.

**Vanish** is for watching without leaving a trace. Invisible, ignored by mobs, fully protected, and —
unlike every other mode — it **refuses to touch the world**: no item pickups, no breaking, placing,
attacking, or using. It stays in survival but swaps to the admin loadout, and includes night vision and
flight. Effects are refused outright, including catching fire, so there is no flame overlay giving you a
lit screen while you sit still.

| Command | Effect |
|---|---|
| `/vanish` | Toggle |
| `/vanish pickups` or `/vanish p` | Allow or refuse picking items up |
| `/vanish interact` or `/vanish i` | Allow or refuse world interaction |

Both modifiers are off by default and persist per player, so you can flip them mid-session without
leaving the mode.

> **Doors, trapdoors and fence gates always work**, even with interaction refused — a mode for moving
> quietly through a building that cannot open a door is not much use. Note the door still swings and
> still makes a sound for everyone nearby: this exempts the interaction, it does not hide it.

**Ghost** is invisible God Mode that leaves you in survival. Unseen and untouchable, still holding your
own gear, with flight available — your game mode and inventory are never touched. That combination is
the one thing **Admin** cannot give you, because Admin forces creative and swaps you onto its own
loadout.

**Build** is deliberately *not* hidden. Creative already makes you invulnerable so hostile mobs ignore
you, and staying visible is the point when building in front of people. It adds night vision and a
reach bonus on both interaction-range attributes.

Any mode that stashes your inventory (Admin, Build) keeps **its own permanent loadout**, so admin tools
and building tools never mix and neither touches your survival gear. Loadouts persist across restarts —
staff never restock.

> **`/admin off` is a full reset.** It clears the state, restores your gear, and pulls you out of
> creative even if the active mode never put you there. Repeating `/admin passive` or `/admin ghost`
> instead just drops that one effect and leaves your game mode alone.

---

## Commands

### Modes

| Command | Aliases | What it does |
|---|---|---|
| `/admin` | | Toggle Admin Mode |
| `/admin off` | | Full reset — clear state, restore gear and game mode |
| `/admin passive` | `/passive` | Toggle Passive Mode |
| `/admin build` | `/build` | Toggle Build Mode |
| `/admin god` | `/godmode`, `/tgm` | Toggle God Mode |
| `/admin demigod` | `/demigod`, `/dg`, `/tdg` | Toggle Demigod |
| `/admin ghost` | `/ghost` | Toggle Ghost |
| `/vanish` | | Toggle Vanish — see above |
| `/nv` | | Toggle night vision (same setting as `/build nv`) |
| `/build nv` | | Toggle Build Mode night vision (persists) |
| `/build reach <0–10>` | | Set your Build Mode reach bonus (persists) |

There is deliberately **no `/gm` alias** — server suites overwhelmingly bind that to gamemode.

### Flight

| Command | What it does |
|---|---|
| `/fly` | Toggle flight |
| `/fly speed <1–5>` | Flight speed multiplier (also affects creative flight) |

Flight is a **perk of the god tier**, not a state: it works in God and Ghost, and in Demigod only if
enabled. Admin and Build already fly natively through creative. Turning it on outside those modes is
refused; turning it off works anywhere so you can clear leftovers.

Losing a flight source mid-air books **one free landing** — no fall damage on the way down. This
survives a relog but not a full server restart.

### Teleport

| Command | What it does |
|---|---|
| `/tp <player>` | Teleport yourself to a player |
| `/tp <player> <player>` | Teleport the first player to the second |
| `/tp <x> <y> <z>` | Teleport to exact coordinates |
| `/tp <x> <z>` | Teleport to that column, picking a safe height |
| `/tp <player> <x> <z>` | Send a player to that column |
| `/top` | Move to the highest standable spot in your column |
| `/tphere <player>` | Bring a player to you |
| `/tpthere <player>` | Send a player to the block you are looking at |
| `/tpall` | Send everyone else to the block you are looking at |
| `/back` | Return to where you last teleported from, or died |
| `/admin tp <player>` | `/atp` — save a return point, force Admin Mode, then teleport |
| `/admin back` | Swap between your saved return point and where you are |

**`/tp` replaces vanilla's `/tp` alias.** Vanilla's `/teleport` is untouched and keeps every form it
ever had — entity selectors, `facing`, explicit rotation — so nothing is lost; operators who want those
use `/teleport`.

`/top` skips **bedrock**, so it will not strand you on the Nether roof, and it checks the space with
your actual bounding box, so it will not drop you somewhere you would suffocate. It also refuses to put
you in lava. **Both two-coordinate forms of `/tp` use the same search**, so `/tp 100 -200` lands
somewhere you can stand rather than on whatever happens to be topmost.

`/tpthere` and `/tpall` need you to be looking at a **solid block** within 256 blocks; looking at the
sky or past that range fails with a reason rather than teleporting anyone somewhere arbitrary. With
`/tpall`, a player who cannot fit — or who is immune — is skipped and reported individually rather than
aborting the whole command.

**Teleport immunity.** A player with `tp.immune` cannot be moved by anyone else; their own teleports
still work. It defaults to *denied* rather than falling back to operator, deliberately — see the
permissions section.

**`/back` and `/admin back` are separate.** They store different locations on purpose: `/admin back`
is the staff ping-pong point and is rewritten by `/admin tp`, which would otherwise stamp over a
player's own last position every time staff teleported. `/back` returns to whichever came last — your
last teleport origin, or where you died if you hold `tp.death`.

### Homes

| Command | What it does |
|---|---|
| `/home` | Teleport to your home |
| `/home set [name]` | Set your home |
| `/home <name>` | Teleport to a named home |
| `/home list` | List your homes |
| `/home delete <name>` | Delete a named home |
| `/admin home <name>` | Staff home tier — teleport |
| `/admin home set <name>` | Staff home tier — set |
| `/admin home list` | Staff home tier — list |
| `/admin home delete <name>` | Staff home tier — delete |

**Your unnamed home *is* your respawn point** — not a copy of it. `/home` sends you to your spawn, and
`/home set` moves your spawn. This is why "your home is your spawn until you sleep in a bed" needs no
special handling: a bed moves the respawn point through vanilla and your home follows.

Player homes and admin homes are **separate tiers**, so the same name can exist in both without
collision. `/admin home` is named-only — exactly one command in the mod moves a respawn point.

Naming is **off by default** (`playerNamedHomes: false`), so out of the box a player has one home and
it is their spawn. With naming off, `/home` shows no name argument at all.

### Presence

| Command | What it does |
|---|---|
| `/afk` | Toggle AFK |
| `/afk <reason>` | Go AFK with a reason shown in the announcement |
| `/afkoff` | Turn the automatic idle timer off for yourself |
| `/afkon` | Turn it back on |
| `/fakeleave [mode]` | Broadcast a leave message and enter a mode (default Ghost) |
| `/fakejoin` | Broadcast a join message and nothing else |
| `/admin grant <mode> <player>` | Put another player into a mode |
| `/admin revoke <player>` | Clear their mode and restore their gear and game mode |

**AFK** kicks in automatically after 90 seconds idle (configurable). While AFK, hunger is frozen and
mobs ignore you. Any activity ends it — movement, keys, clicks, chat, **and looking around**, since
mouse movement reaches the server as head rotation. A vanished player's AFK announcement is sent only
to them, so it cannot give away someone who is hiding.

**Running a command does not count as activity; typing in chat does.** Otherwise `/afk` would cancel
itself the moment you ran it, and an AFK player checking `/tps` would silently come back.

Two things suppress the automatic timer. **`/fakeleave` blocks it** — someone who has announced a fake
departure is being deliberately quiet, and marking them AFK would broadcast their name. And **`/afkoff`
exempts a player entirely**, persistently, while leaving `/afk` itself working: deciding you are away is
always yours to make. Turning it off while already AFK also releases you.

**`/fakeleave`** accepts any mode including `none`, so you can have the server announce you left while
standing in plain sight. The messages are built from vanilla's own translation keys, so anything a
resource pack or language setting rewords is reworded here too.

`/fakejoin` broadcasts and clears the "Offline Mode" label but **does not change your mode** — coming
off duty stays a separate, explicit act.

**`/afk` is refused while you are appearing offline.** AFK is a presence signal and `/fakeleave` says you
are not here — the two must not coexist. Going AFK first and then using `/fakeleave` silently clears the
AFK state for the same reason.

### What vanish actually hides

Vanish works at the **entity-tracking** layer: a hidden player's entity is never sent to clients that
should not see them, so there is nothing for a client to render, hit, or read out of its entity list.

| | Hidden? |
|---|---|
| Seeing the player in world | yes |
| Tab list | yes |
| Locator bar waypoint | yes |
| Join and leave messages | yes |
| `/list` and its player count | yes |
| AFK announcements | yes |
| **Client-side minimaps** (JourneyMap, Xaero's, VoxelMap) | **yes** |
| **BlueMap** and **squaremap** | **yes**, via their APIs |
| Server-list ping count and name sample | yes |
| **Dynmap** | **no** — see below |
| Anything they *do* — chat, block changes, container use | no |

Client-side minimaps can only draw what their client was told about, and it is never told. That is
precisely why vanish is done at the tracker rather than with an invisibility effect — an invisible
player is still an entity the client knows about, and a radar would happily draw it.

Web maps are the exception that needs explicit work: they read the server's player list directly and
never touch the packets sent to clients. BlueMap and squaremap are handled through their own APIs, as
soft dependencies — if neither is installed, nothing is loaded and nothing changes.

> **Dynmap is not covered.** It has no Fabric build for Minecraft 26.2, so there is nothing to test
> against; an untested integration would be worse than a documented gap. If you run Dynmap, treat
> vanished players as visible on it.

Two switches, both on by default:

| Key | Effect when off |
|---|---|
| `hideFromPing` | The server-list count and sample stay truthful, so vanished staff are counted |
| `hideFromWebMaps` | Vanished players stay visible on BlueMap and squaremap |

`hideFromPing` matters more than it looks: the ping is readable by anyone who can reach the port,
without logging in, so a count that drops when staff go on duty is a tell that needs no game client at
all. Turn it off only if something downstream — a queue plugin, a server list tracking population —
needs the true number.

### Public

| Command | What it does |
|---|---|
| `/mode` | Your current mode, what it does, and any active flags |
| `/mode <name>` | Describe any mode without entering it |
| `/tps` | Current ticks per second and MSPT |
| `/ping` | Replies "Pong!" — proves the connection is alive |

`/mode` is there for players without the client jar, who get no indicator, and for anyone who cannot
remember which mode stops mobs and which stops damage. It also reports flags that sit alongside the mode
— flight, AFK, appearing offline — and, in Build Mode, your current reach and night-vision settings.

`/tps` exists because vanilla's `/tick query` sits behind op 3, so players cannot use it.

### Administration

| Command | What it does |
|---|---|
| `/arkon config` | List every setting |
| `/arkon config <key>` | Show one setting and its description |
| `/arkon config <key> <value>` | Change a setting, applying immediately |
| `/arkon reload` | Re-read a hand-edited config file |
| `/arkon perms <player\|uuid>` | Show how every permission resolves for someone |

**`/arkon` is operators only and has no permission node.** It changes server-wide behaviour for
everyone, so no staff grant reaches it.

`/arkon perms` accepts an offline UUID, so you can audit a moderator's setup without them logged in.
Each node reads `granted`, `denied` or `default` — where *default* means nothing decided it and the
fallback applied. Note that **for an operator the fallback is always yes**, so tiers must be tested on
an unopped account.

---

## Permissions

Nodes are `arkonessentials:<node>`, which permission mods usually write dotted:
`arkonessentials.build`.

### Public — allowed unless you revoke them

| Node | Grants |
|---|---|
| `tps` | `/tps` |
| `ping` | `/ping` |
| `home` | `/home` |
| `afk` | `/afk` |
| `mode` | `/mode` |

### Config-defaulted — fall back to a setting, not to operator

| Node | Grants | Default from |
|---|---|---|
| `home.named` | Naming your own homes | `playerNamedHomes` (false) |
| `home.limit` | *(integer)* how many homes | `playerHomes` (1) |
| `admin.home.limit` | *(integer)* how many staff homes | `adminHomes` (5) |
| `build.nv` | `/build nv` | `buildNightVisionAvailable` (true) |
| `fly.demigod` | Flight while in Demigod | `demigodFlight` (false) |
| `afk.reason` | `/afk <reason>` | `afkReasonsAvailable` (true) |

### Staff — granted, or else operator

| Node | Grants |
|---|---|
| `passive` | Passive Mode |
| `build` | Build Mode |
| `build.reach` | `/build reach` |
| `god` | God Mode |
| `demigod` | Demigod |
| `vanish` | Vanish, and its two modifiers |
| `fly` | `/fly` |
| `fly.speed` | `/fly speed` |
| `tp` | `/tp <player>` |
| `tp.others` | `/tp <player> <player>` |
| `tp.coords` | `/tp` to coordinates |
| `tp.back` | `/back` |
| `tp.death` | `/back` also returns to your death location |
| `tp.top` | `/top` |
| `tp.here` | `/tphere` |
| `tp.there` | `/tpthere` |
| `tp.all` | `/tpall` |
| `afk.toggle` | `/afkon` and `/afkoff` |
| `admin.grant.<mode>` | `/admin grant` for that one mode, and `/admin revoke` |
| `fake.leave` | `/fakeleave` |
| `fake.join` | `/fakejoin` |
| `admin.mode` | Admin Mode |
| `admin.ghost` | Ghost |
| `admin.tp` | `/admin tp`, `/atp`, `/admin back` |
| `admin.home` | The `/admin home` tier |
| `admin.see_hidden` | Seeing vanished players |

### Immunities — granted only, never inherited from operator

| Node | Protects against |
|---|---|
| `tp.immune` | Being teleported by another player |
| `admin.grant.immune` | Having a mode granted or revoked by another player |

These two are checked **strictly**: true only if something explicitly granted them. Every other staff
node falls back to operator level, and for an immunity that fallback would be actively wrong — it would
make every operator immune to every other operator, and on a server with no permissions mod it would
make your whole staff untouchable by each other. Neither applies to yourself, so holding one never
locks you out of your own commands.

Note that `admin.grant.immune` sits under `admin.grant.`, so a wildcard grant of
`arkonessentials.admin.grant.*` confers immunity along with the ability to grant. That is usually what
you want for senior staff, but deny the child explicitly if not.

### Things that will bite you

**Operator level satisfies every staff check.** This fallback has to exist — vanilla answers `false` to
mod-defined permissions at *any* op level, so without it a lone server owner with no permissions mod
would be locked out of their own mod. The consequence: an **opped** builder also has god mode. Tiered
staff must be left **unopped** and given nodes through a permissions mod.

**Dotted nodes inherit from their parent.** Granting `arkonessentials.build` also grants `build.nv` and
`build.reach`. To grant Build Mode while withholding night vision, set the child to `false` explicitly.
Verified against LuckPerms.

Because of that, death-return is `tp.death` and **not** `tp.back.death` — nesting it would hand it out
with every `/back` grant. Anywhere a child needs to be *stricter* than its parent, the nodes are
siblings instead.

**Nothing implies anything else.** There is no hardcoded "admin confers ghost". Groups and inheritance
belong in your permissions mod, where an Admin group lists what it grants or uses a wildcard like
`arkonessentials.admin.*`. That is deliberate — hardcoding it would stop you composing a group that
excludes something.

### Suggested tiers

| Group | Nodes |
|---|---|
| Player | *(nothing — the public nodes already work)* |
| Passive | `passive` |
| Builder | `build`, `build.reach`, `fly.speed` |
| Moderator | `admin.ghost`, `tp`, `tp.back`, `tp.top`, `admin.see_hidden` |
| Admin | `arkonessentials.*` |

Passive alone is a safe mode for a young player. Builder gets build tools with no invisibility.
Moderator gets moderation without creative.

---

## Configuration

### Server — `config/arkonessentials.json`

Every key is editable in game with `/arkon config <key> <value>` and takes effect immediately, with no
relog. `/arkon reload` re-reads a file you edited by hand.

| Key | Default | Meaning |
|---|---|---|
| `playerHomes` | `1` | How many homes a player may keep. `0` disables `/home`. |
| `playerNamedHomes` | `false` | Whether players may name homes. |
| `adminHomes` | `5` | How many named homes under `/admin home`. |
| `defaultBuildReach` | `4` | Build Mode reach bonus for players who set none. |
| `buildNightVisionAvailable` | `true` | When false, `/build nv` needs `build.nv`. |
| `defaultFlySpeed` | `2` | Flight speed for players who set none. |
| `demigodFlight` | `false` | Whether Demigod grants flight. |
| `afkTimeoutSeconds` | `90` | Idle seconds before auto-AFK. `0` disables the timer. |
| `afkMessage` | `%s has gone AFK!` | Going-AFK announcement. `%s` is the name. |
| `afkReasonMessage` | `%s has gone AFK. Reason: %s` | With a reason. Name, then reason. |
| `afkReturnMessage` | `%s is no longer AFK.` | Returning announcement. |
| `afkReasonsAvailable` | `true` | When false, `/afk <reason>` needs `afk.reason`. |
| `fakeLeaveDefaultMode` | `ghost` | Mode `/fakeleave` uses when none is named. |
| `hideFromPing` | `true` | Leave vanished players out of the server-list ping count and sample. |
| `hideFromWebMaps` | `true` | Hide vanished players on BlueMap and squaremap, when installed. |

**The defaults are live.** Per-player preferences (reach, fly speed, night vision) are stored as
*absent-until-set*, so changing a default here moves every player who never chose their own.

A config file that fails to parse is **left strictly alone** and defaults apply for that run. A stray
comma will never cost you your settings.

### Client — `config/arkonessentials-client.json`

A separate file, edited through **Mod Menu → Arkon Essentials**. Purely cosmetic: nothing in it can
change permissions or behaviour, it never syncs, and no server reads it.

| Setting | Meaning |
|---|---|
| Show Indicators | Master switch for the whole HUD element |
| Text Shadow | Drop shadow behind the labels |
| Screen Corner | Which corner to anchor to |
| Text Scale | 50–300% |
| Horizontal / Vertical Offset | Inset from the anchored corner |
| Per-indicator toggle | Hide any single indicator |
| Per-indicator colour | Hex entry, previewed in the colour it names |

Indicators: Admin Mode, Passive Mode, Build Mode, God Mode, Demigod, Ghost, Flight Enabled, AFK,
Offline Mode. The last four stack under the active mode, since they are flags rather than states.

Colours left untouched follow the mod's built-in ones, so a future change to a default reaches you
instead of being pinned to whatever it was the day you installed.

---

## Notes for operators

- **Vanished players can see each other.** Two people on the same incident being invisible to one
  another is worse than useless.
- **Vanish suppresses join and leave messages**, removes the tab-list entry for players without
  `admin.see_hidden`, and drops the locator-bar waypoint. The waypoint goes for *everyone* including
  ops, who can still see the player directly.
- **`/kill` does not work on God or Demigod.** That is the intended reading of "prevents health from
  degrading", but it does surprise people — leave the mode first.
- **Leaving Build Mode strips night vision**, including a potion you drank yourself.
- State, loadouts, homes and preferences **persist to disk**, not memory, so a crash mid-admin-mode
  cannot destroy your inventory. AFK and Offline Mode are session-only by design.
- Upgrading is safe; **downgrading is guarded**. If the save file was written by a newer build, the mod
  loads empty, refuses to write, and says so in the log rather than quietly destroying data it does not
  understand.

## Licence

MIT.
