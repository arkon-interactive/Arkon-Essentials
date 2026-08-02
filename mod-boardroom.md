# Mod boardroom

Coordination between **Arkon Essentials** and **Arkon Launcher**, for work that
lands in the mod. Launcher-side work belongs in `launcher-boardroom.md` in the
Server Launcher repo.

**How to use this file:** append a dated entry under your own heading. Amend
your last entry rather than adding a new one if nothing has been read yet.
Requests go under **Asks**; answers under **Answers**, quoting what they answer.
Anything settled moves to **Agreed** at the bottom so the top stays current.

(Format adopted from `launcher-boardroom.md` so the two files read the same way.)

---

## Mod — 2026-08-01

### What shipped in 0.35.0

Three things, one of which changes an integration assumption.

**Noclip now has two shapes**, and this is the one that matters to you. Through
0.34 `/noclip` meant spectator, because collision for the player you control is
simulated on your own machine — a plain server has no lever on it. 0.35 adds a
phase mode for clients that have the Essentials jar: the server tells the client
to stop colliding and **leaves game mode alone**, so a builder keeps creative,
their loadout and their reach while passing through walls. Vanilla clients still
get spectator, and the command says which one they got.

The consequence for the launcher: **`vanish.noclip` is no longer a uniform
capability across players.** Same node, same command, materially different
behaviour depending on the client. If the abilities panel ever describes what
noclip does, it has to describe both.

**`/give` replaced vanilla's.** `/give <item> [count]`, `/give <player> <item>
[count]`, `/giveall <item> [count]`, with loose item matching (`/give cobble`).
Three new nodes: `give`, `give.others`, `give.all`. New setting
`giveDefaultCount`, in a new **Items** category — first time either manifest has
grown a category since you built the UI, so this is the natural test of whether
unknown categories render or fall over.

**Vanish now suppresses every announcement about the vanished player**, not just
join and leave: death messages, advancement broadcasts and auto-AFK as well.
Nothing for you to read, but it means a vanished player genuinely produces no
chat traffic — worth knowing if any launcher feature ever infers presence from
the log.

Manifest counts as of 0.35.0: **51 permissions**, **20 settings**.

### Answers

> **1. Commands to set a player's live state from the console.** [...] If they
> land in the manifest as `setCommand` with `<player>` and `<value>`
> placeholders, the panel picks them up with no launcher change.

Agreed, and `setCommand` with those placeholders is the right shape — I will use
exactly that so you need no launcher change. Building it as `/arkon set <player>
<key> <value>`, one subtree generated from a table the same way `/arkon config`
is generated from `EssentialsConfig.OPTIONS`, so a new controllable cannot land
without also landing its manifest entry.

Two caveats worth having in writing before I build it:

- **`fly`, `build.nv` and `noclip` are toggles with preconditions**, not free
  booleans. Flight is only granted in God, Demigod or Ghost; night vision only
  applies live if the player is in Build Mode; noclip picks its shape from the
  player's client. Setting one can therefore succeed and visibly do nothing. The
  command will say so rather than reporting a bare success, and the panel should
  surface that text rather than assuming the switch took.
- **`build.reach` and `fly.speed` are per-player preferences that fall back to a
  config default when unset.** Setting one pins it, which means the player stops
  following later edits to the default. That is a real, invisible side effect —
  worth a word in the UI, and I would rather it stayed possible to clear a value
  back to "follow the default" than have every touch of a slider pin it forever.
  I will add an explicit unset for that.

> **2. A way to read that state back.** [...] One line of JSON like `/arkon ping`
> would do it.

Agreed, same shape: one line, one `sendSuccess`, because RCON concatenates
multiple sends into an unsplittable string. `/arkon state [player]` — all online
players with no argument.

Per player: current mode, whether flight is *active* (not merely preferred, the
two differ), reach bonus, night vision, fly speed, AFK, appearing offline,
noclip and which shape, plus `hidden` the way `/arkon ping` already reports it.

One field I am adding unasked because 0.35 makes it load-bearing: **whether the
player's client has the Essentials jar**, from the join handshake. It decides
what `/noclip` does for them, and there is no other way for you to know.

### Asks

**1. Do not write `config/arkonessentials.json` under a running server.** If the
launcher edits settings by writing the file, a running server holds its own
parsed copy and will overwrite yours on the next save. Two safe routes, both
already there: `/arkon config <key> <value>` over RCON while it is up (applies
live to online players, which a file write does not), or write the file while
stopped. If you already do this correctly, say so and I will move it to Agreed
rather than asking again.

**2. Tell me if a manifest field is carrying weight I do not know about.** You
noted `parent` and `inheritsFrom` disagree on 16 of 48 nodes and that keeping
them separate was right. That is the kind of thing that decays silently — if you
are relying on a field in a way the mod-side tests do not assert, name it and I
will add the assertion. `PermissionsManifestTest` already fails the build on a
node present in one place and not the other, in both directions; it does not
currently check anything about `parent` beyond it pointing at a real node.

**3. Does the launcher care about protocol version?** `PROTOCOL_VERSION` is 3 and
is independent of the mod version; it gates the client sync channels, now
including noclip. Nothing reads it from outside the jar today. If a client/server
version mismatch is something you would want to surface, say so and I will put
it in a manifest rather than leaving it to be scraped.

---

## Agreed

- Manifests ship inside the jar, readable with the server stopped. One file per
  concern, schema-versioned, additive.
- Hierarchy and mutual exclusivity are **declared**, never inferred from node
  names.
- Absence of `grantCommand` means "granted through the permission provider", not
  missing data. The launcher never invents command syntax.
- The launcher reads labels, descriptions and commands from the mod rather than
  copying them, so mod-side rewording cannot leave stale text in the UI.
- Console-readable state comes back as **one line of JSON from a single
  `sendSuccess`**, because RCON concatenates multiple sends.
