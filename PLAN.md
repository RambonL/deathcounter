# DeathCounter — Design & Implementation Plan

Server-side Fabric mod for MC 26.2. Counts player deaths, stores the full
history including coordinates, shows the counter in the tab list and in chat.

## Status

- [x] Clean up template (mixins, datagen, `environment: "server"`)
- [x] `Config` (Gson, `config/deathcounter.json`)
- [x] `DeathData` (SavedData + DeathRecord)
- [x] `DeathCounter` (init, AFTER_DEATH hook, scoreboard sync, broadcast)
- [x] `DeathCommands` (Brigadier, message building, pagination)
- [x] Test with `./gradlew runServer` and a vanilla client

## Principles

- **Server-side only.** `environment: "server"`, no custom packets, no custom
  registries — vanilla clients must be able to connect.
- **No mixins.** Fabric API covers everything we need. `ExampleMixin` and
  `deathcounter.mixins.json` are deleted.
- **No new dependencies.** Gson ships with Minecraft, Brigadier with the
  server, SavedData is vanilla.

## Death capture

`ServerLivingEntityEvents.AFTER_DEATH`, filtered to `ServerPlayer`. The event
fires before respawn, so the entity is still at the death position.

## Data model

```
Death: timestamp, dimension, pos, causeId, message
Entry: uuid -> { name, deaths[] }
```

- **UUID as the key**, `name` only for display and autocomplete. Renames
  therefore break nothing; `add()` refreshes it on every death.
- `causeId` from `DamageSource#getMsgId()` (`"mob"`, `"fall"`, `"creeper"`) —
  groupable for the cause breakdown.
- `message` stored alongside as the ready-made vanilla component, so display
  code does not have to reconstruct the text.
- Dimension is mandatory, otherwise the coordinates are worthless (nether X ≠
  overworld X).
- No `total` field: with uncapped history it is always `deaths.size()`, and a
  second value claiming the same thing eventually disagrees.
- No `killer` field: it is already in `message`, and grouping goes by `causeId`.

## Storage

`SavedData` in the server-wide storage (`MinecraftServer#getDataStorage()`) →
`world/data/deathcounter/deaths.dat`, next to vanilla's `scoreboard.dat`. The
namespace of the `Identifier` becomes a directory
(`Identifier#resolveAgainst`). Written automatically when the world saves.

Not `ServerLevel#getDataStorage()` — that one is per dimension and lands in
`world/dimensions/<ns>/<path>/data/`, which would mean one counter for the
overworld, one for the nether and one for the end.

`SavedDataType` takes a nullable data fixer type as its fourth argument; we
pass `null` because our data has no legacy formats to migrate.

Full history, no cap. Call `setDirty()` only on death and on config change —
if nothing changed, vanilla skips the write.

> `ponytail:` everything in memory, full rewrite per autosave. Move to SQLite
> or per-player files if death counts reach six figures.

## Coordinate visibility

Enum `coordVisibility`, three levels. Ops always see everything through the
`admin` branch.

| Mode     | Broadcast | Own coords | Other players' coords |
|----------|-----------|------------|-----------------------|
| `HIDDEN` | without   | no         | op only               |
| `SELF`   | without   | yes        | op only               |
| `PUBLIC` | with      | yes        | anyone                |

**Exactly one check:** `maySeeCoords(source, targetUuid, admin)`. Every output
path goes through it, otherwise some command will eventually leak coordinates
past the configured mode.

## Config

`config/deathcounter.json`, one key:

```json
{ "coordVisibility": "SELF" }
```

Gson, no config framework. Load in `onInitialize`, write defaults if the file
is missing. Setting the value via command writes the file back, otherwise the
change is gone after a restart.

More keys only when actually needed — broadcast and tab list are fixed.

## Display

- **Tab list:** own `dummy` objective, display slot `list`, score written from
  our own counter. Deliberately **not** the `deathCount` criterion — two
  independent counters would drift apart. Synced on death and on join.
- **Chat broadcast to everyone** on each death, as a *second* line below the
  vanilla death message: `Rambo — death #42`. Vanilla sends its own message
  from `ServerPlayer#die()`; replacing it would need a mixin, so we do not.
  Coordinates only in `PUBLIC` mode.

## Commands

```
/deaths                            own stats + cause breakdown
/deaths <player>                   that player's stats
/deaths top [page]                 leaderboard by total deaths
/deaths last [player]              last death, coords per mode
/deaths history [player] [page]    history, 10 per page, coords per mode

/deathsadmin last [player]         same, coords always, TP link attached  (op)
/deathsadmin history <p> [page]    same, coords always, TP links attached (op)
/deathsadmin tp <player> <idx>     teleport to the death spot             (op)
/deathsadmin reset <player>        wipe counter + history                 (op)
/deathsadmin config coords <…>     set visibility + persist               (op)
/deathsadmin config reload         reload config from disk                (op)
```

`deathsadmin` is not a separate feature branch — it is the same command with
the visibility check disabled plus TP links.

**Implementation:** do not write handlers twice. Every command takes a
`boolean admin`, passed down to `maySeeCoords(…)`. The subtree is built once
and registered twice: under `/deaths` with `admin=false`, and under
`literal("deathsadmin").requires(hasPermission(LEVEL_GAMEMASTERS))` with
`admin=true`.

`top` and the stats view are not mirrored — they contain no coordinates, so
admin mode would produce identical output.

### Why a separate root command, not `/deaths admin`

`CommandDispatcher#getCompletionSuggestions` does **not** apply the `requires`
predicate — only `parse` and the tree sent to clients (`fillUsableCommands`)
do. Literals are completed from the client's own filtered tree, but an
argument with custom suggestions makes the client ask the server, and the
server then collects suggestions from *every* sibling, gated or not.

With `/deaths admin`, the `admin` literal sat next to `<player>`, so typing
`/deaths ` suggested `admin` to everyone. Execution stayed blocked, but the
suggestion was there. Separating the roots removes the sibling relationship.

Do not fold `deathsadmin` back under `/deaths` as a subcommand.

## Pagination

Plain text, no buttons: `Page 2/7 — next: /deaths history Rambo 3`. Saves
component building and edge cases, costs one keystroke.

## Clickable teleport

Only the coordinates themselves are clickable: `ClickEvent` with `RUN_COMMAND`
pointing at `/deaths admin tp`, `HoverEvent` showing dimension, full
coordinates and cause of death.

It runs in the context of the clicking player, so the permission check applies
automatically — non-ops simply see no link. The teleport carries the dimension
along (`teleportTo(level, …)`).

**Deliberately not handled:** dying in lava or the void teleports you exactly
there. This is an op tool, and `/gamemode spectator` already solves it.

## Known edges

- `getCompletionSuggestions` ignores `requires`, see above. This is why the
  operator commands live under their own root.
- Uncapped history makes pagination mandatory, not optional.
- Style set on a root component bleeds into everything appended to it, and a
  plain colour on a child does not reset bold. Headers therefore sit inside an
  empty root rather than being the root.
- Killing the dev server with `pkill` skips the world save and loses every
  death since the last autosave. Use `stop` on the console.