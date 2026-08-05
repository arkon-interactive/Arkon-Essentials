# Arkon Essentials API — draft

**Status: proposed, not built.** This is a design for review. Nothing here exists yet; the shapes are
open to change until the launcher agrees to them. Where it supersedes something already promised in
`mod-boardroom.md`, that is called out explicitly.

---

## What problem this actually solves

It is worth being precise, because the obvious answer is the wrong one.

Today a tool can reach the mod three ways: two manifests inside the jar (`permissions.json`,
`settings.json`), a handful of RCON commands, and nothing else. That works, and the launcher is built on
it. What it lacks is not *reach* — it is **consistency and self-description**:

| | Today | Consequence |
|---|---|---|
| `/arkon ping` | `{"schema":1,"players":[…]}` | Its own shape, invented for itself |
| `/arkon perms <player>` | Human prose, aligned columns | Parseable only by regex, breaks on rewording |
| `/tps` | Human prose | Same, and it is a *player* command so it must stay prose |
| Errors | `sendFailure` prose | No code, no way to tell "no such player" from "server busy" |
| Discovery | Read the README | A tool cannot ask what calls exist |

So the thing to add is **one envelope, one error convention, and a machine-readable description of every
call** — not a new way to talk to the server. That distinction drives the recommendation below.

---

## Recommendation: do not build an HTTP server (yet)

If "API" brought a REST endpoint to mind, here is the case against, and the case for revisiting later.

**Against, now:**

1. **A new port.** Port pressure is already real on this box — 25565 belongs to the hosting software, and
   the dev server has to live at 25566+. An API port is one more thing to allocate, document, and
   collide with.
2. **New authentication.** A token to generate, store on both sides, rotate, and leak. RCON already has
   an authenticated channel that the launcher already speaks and the operator already configures.
3. **New attack surface on a deliberately exposed machine.** The whole point of the launcher is hosting
   for other people, so the server *is* internet-facing. An HTTP listener is a second front door. RCON's
   is at least a door operators already know to think about.
4. **It buys nothing the launcher needs.** The launcher runs on the same machine as the server and polls.
   Polling three calls at 1 Hz over RCON is unremarkable.

**For, later — these are the triggers that would make HTTP correct:**

- **Scoped, read-only access.** This is the strongest one. RCON is all-or-nothing: `RconConsoleSource`
  builds its source at `LevelBasedPermissionSet.OWNER`, so anything holding the RCON password can run
  anything. The moment you want a Discord bot or a status page to read player counts *without* handing it
  the keys to the server, RCON is the wrong tool and a scoped bearer token is the right one.
- **Push instead of poll.** SSE or a websocket for live state. RCON cannot push, ever.
- **A browser-reachable dashboard**, where `curl`-ability and status codes genuinely matter.

**So: design the contract transport-agnostically, bind it to RCON now.** Every call below is a name, an
argument list and a JSON response — none of which mention RCON. If HTTP is added later it becomes a
second binding of the same contract (`GET /v1/players` → the `players` call), and the launcher's parsing
does not change. That is the part worth getting right today, and it costs nothing to do so.

---

## The transport constraint that shapes everything

Two facts about vanilla RCON, both verified against the 26.2 sources rather than assumed. They are not
incidental — they dictate the response format.

**1. Multiple `sendSuccess` calls are concatenated with no separator.**

```java
// RconConsoleSource
public void sendSystemMessage(final Component message) {
    this.buffer.append(message.getString());   // no delimiter, no newline
}
```

So **one call must produce exactly one `sendSuccess`.** Two lines arrive as one unsplittable string. This
is already documented for `/arkon ping`; it applies to everything here.

**2. Responses are chunked at 4096 characters, with no terminator.**

```java
// RconClient.sendCmdResponse
do {
    int dataLen = 4096 <= len ? 4096 : len;
    this.send(requestid, 0, response.substring(0, dataLen));
    response = response.substring(dataLen);
    len = response.length();
} while (0 != len);
```

Every chunk carries the same request id and nothing marks the last one. A client that reads a single
packet — which most simple RCON clients do — **silently truncates at 4096 characters and gets invalid
JSON.**

This is the single most important consequence in this document: **pagination is mandatory, not a
nicety.** A player entry with name, UUID, mode and the eight flags runs roughly 200–250 characters, so an
unpaginated player list breaks somewhere around **17 players** — well inside the size of a friends
server, and it would fail in production rather than in testing.

Every list-returning call therefore takes a page argument and reports whether more remains. The page size
is chosen by the server so the client never has to reason about byte budgets.

---

## Envelope

Every call returns exactly one line of JSON with the same outer shape.

**Success:**

```json
{"ok":true,"schema":1,"call":"players","data":{"…":"…"}}
```

**Failure:**

```json
{"ok":false,"schema":1,"call":"players","error":{"code":"NO_SUCH_PLAYER","message":"No player named 'Steve'."}}
```

| Field | Why it is there |
|---|---|
| `ok` | First key, so a client can branch before parsing the body. Never absent. |
| `schema` | **Per call, not global.** Calls evolve independently; a global number would force a bump on every consumer for a change to one call. |
| `call` | Echoed back. RCON has no request correlation, so a client that pipelines has nothing else to match on. |
| `data` / `error` | Exactly one is present. Never both, never neither. |

**Error codes are a closed set**, listed in the descriptor so a client can enumerate them:
`NO_SUCH_PLAYER`, `NO_SUCH_KEY`, `INVALID_VALUE`, `NOT_APPLICABLE`, `PAGE_OUT_OF_RANGE`, `INTERNAL`.

`NOT_APPLICABLE` earns its place: it is the answer when a call succeeds mechanically but cannot take
effect — setting flight on a player whose mode does not grant it, or night vision on someone not in Build
Mode. Returning `ok:true` there would make a launcher switch flip and appear to work. This is the caveat
already raised in `mod-boardroom.md`; the error code is how it becomes machine-readable instead of prose
the panel has to display and hope the user reads.

**Paginated responses** carry their own envelope inside `data`:

```json
{"ok":true,"schema":1,"call":"players",
 "data":{"page":0,"pages":3,"total":42,"items":[…]}}
```

`pages` rather than a `hasMore` boolean, so a client can size a progress bar or fetch in parallel.

---

## Calls

### `arkon api` — the descriptor

Returns the same document shipped in the jar as `assets/arkonessentials/api.json`. Two ways to get it, on
purpose: **from the jar with the server stopped**, which is the pattern already agreed for the other
manifests, and **from the running server**, so a launcher can confirm the server it is talking to matches
the jar it read. A mismatch there is exactly the sort of thing that otherwise shows up as a baffling
parse error.

The descriptor carries, for every call: its name, arguments with types and whether they are required,
the response schema version, a description, and the error codes it can return. Field-level descriptions
are included too — this is the "associated doc entries" part, and it means the launcher renders help text
from the mod rather than keeping its own copy in step.

### `arkon api server` — one shot, everything about the server

```json
{"tps":19.98,"mspt":12.4,"tickrateTarget":20.0,"ticks":184203,
 "players":{"online":4,"max":20,"hidden":1},
 "mod":{"version":"0.35.0","protocol":3},
 "minecraft":"26.2"}
```

`tps` uses the same computation as `/tps` — `min(target, 1e9 / averageTickTimeNanos)`, with the target
read from the tick rate manager rather than hardcoded to 20. Never the naive `1e9 / averageTickTime`,
which reports ~3300 TPS on an idle server.

`players.hidden` is a count, not names — see the note on vanish below.

Fixed size, so it never paginates.

### `arkon api players [page]` — live state for everyone

**This supersedes the `/arkon state` promised in `mod-boardroom.md`** — same content, same one-line-JSON
shape, inside the common envelope. The launcher is blocked on this, so the rename needs their sign-off
rather than being imposed; it is one string on their side.

```json
{"name":"Steve","uuid":"…","ping":42,
 "mode":"build","hidden":false,
 "flight":{"active":true,"speed":2},
 "build":{"reach":4,"nightVision":true},
 "afk":false,"appearingOffline":false,
 "noclip":{"active":false,"shape":"phase"},
 "client":{"hasMod":true}}
```

Two fields worth explaining:

- **`flight.active`, not the preference.** These differ: flight is `preference ∧ stateGrantsFlight`, so a
  player can prefer flight and not have it. A panel showing the preference would show a switch that is on
  while the player falls.
- **`noclip.shape`** is `phase` or `spectator`, and **`client.hasMod`** is what decides it. Since 0.35.0
  the same node and the same command produce materially different behaviour depending on the client, and
  `ServerPlayNetworking.canSend` is the only way to know which. Both are reported even when noclip is
  off, because a panel wants to *label the button* before it is pressed.

`client.hasMod` comes from `canSend`, which needs no storage. Reporting the client's **mod version** as
well would need the join handshake retained — it is currently logged and dropped. Cheap to add if the
launcher wants it; left out of the draft rather than assumed.

### `arkon api player <name|uuid>` — one player, unpaginated

Same entry shape, plus the things too heavy for a list: homes, saved locations, loadout summary. Always
one player, so it always fits.

Accepts an offline UUID, like `/arkon perms` does.

### `arkon api permissions <name|uuid> [page]` — resolved gates

The machine-readable twin of `/arkon perms`. Per node: `node`, `granted` / `denied` / `default`, and the
fallback that decided it. 51 nodes at ~70 characters each is roughly 3.5 KB — under the limit today, over
it after a dozen more nodes, so it paginates from the start rather than acquiring pagination later as a
breaking change.

### `arkon api settings` / `arkon api settings set <key> <value>`

Generated from `EssentialsConfig.OPTIONS`, the same source `/arkon config` is generated from, so a new
setting appears here the moment it is declared — no second list.

Reads return current values with their bounds and descriptions. Writes apply live via
`applyToOnlinePlayers`, which is what makes an edit take effect without a relog.

### `arkon api set <player> <key> <value>` — live player state

The launcher's blocking ask. Keys mirror the `players` response: `mode`, `flight`, `flight.speed`,
`build.nv`, `build.reach`, `noclip`, `afk`.

**This does not replace `setCommand` in `permissions.json`.** That still lands as promised, with
`<player>` and `<value>` placeholders, so the launcher's existing substitution keeps working with no
change. The two agree because the manifest entry names this call.

Returns `NOT_APPLICABLE` rather than a bare success when the precondition is not met, and — per the
boardroom — a value key accepts `default` to **unset** a pinned preference and return the player to
following the config. Without that, every touch of a slider pins someone off the default permanently.

---

## Things this deliberately does not do

- **No writes to the world.** No teleporting, no giving items, no kicking. Those are commands, they have
  permission nodes and audit trails, and a tool should call them by name. This surface is for *reading*
  state plus the narrow set of per-player settings the launcher needs. Widening it later is easy;
  narrowing it after something depends on it is not.

- **No vanished-player names in aggregate counts.** `server` reports `hidden` as a count because the
  operator's own tooling should be able to see that staff are on duty. But nothing here should become a
  way to enumerate vanished players to a *third-party* tool. RCON is operator-level so it does not matter
  today; it matters the moment a scoped HTTP token exists, and the shape should not have to change then.

- **No streaming, no subscriptions.** RCON cannot push. Anything wanting live updates polls, and the
  contract is designed so an eventual HTTP binding can add push without changing any payload.

- **No replacement of the human commands.** `/tps`, `/ping`, `/arkon perms` keep their prose output.
  `/tps` is a public player command; reformatting it as JSON to serve a launcher would be letting the
  tooling tax the players.

---

## Open questions for the launcher

1. **`/arkon state` → `arkon api players`.** Accept the rename, or should the alias stay?
2. **Client mod version** — worth retaining the handshake to report it, or is `hasMod` enough?
3. **Page size** — server-chosen is simplest, but say so if a fixed size makes the panel's paging easier.
4. **Is `arkon api settings` redundant** given `settings.json` plus `/arkon config`? It is the only way to
   read *current* values rather than defaults, but confirm that is actually wanted before it is built.
