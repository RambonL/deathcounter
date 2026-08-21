# DeathCounter — Design & Implementation Plan

Server-side mod for MC 26.2, on Fabric and NeoForge. Counts player deaths,
stores the full history including coordinates, shows the counter in the tab list
and in chat.

## Status

- [x] Clean up template (mixins, datagen, `environment: "server"`)
- [x] `Config` (Gson, `config/deathcounter.json`)
- [x] `DeathData` (SavedData + DeathRecord)
- [x] `DeathCounter` (init, AFTER_DEATH hook, scoreboard sync, broadcast)
- [x] `DeathCommands` (Brigadier, message building, pagination)
- [x] Test with `./gradlew runServer` and a vanilla client
- [x] Import from vanilla statistics (`/deathsadmin import`)
- [x] Headless JUnit tests — see [TESTING.md](TESTING.md)
- [x] NeoForge alongside Fabric — see [MULTILOADER.md](MULTILOADER.md)

## Principles

- **Server-side only.** `environment: "server"` / `Dist.DEDICATED_SERVER`, no
  custom packets, no custom registries — vanilla clients must be able to
  connect.
- **No mixins.** Both loaders have an event for everything we need.
  `ExampleMixin` and `deathcounter.mixins.json` are deleted.
- **No new dependencies.** Gson ships with Minecraft, Brigadier with the
  server, SavedData is vanilla.
- **One source tree.** `src/main/java` imports no loader; `fabric/` and
  `neoforge/` compile it and supply an entrypoint each. See
  [MULTILOADER.md](MULTILOADER.md).

## Death capture

Fabric: `ServerLivingEntityEvents.AFTER_DEATH`, filtered to `ServerPlayer`. The
event fires before respawn, so the entity is still at the death position.

NeoForge: `LivingDeathEvent` at `EventPriority.LOWEST`. That one fires *before*
the death and is cancellable, but listeners skip cancelled events by default, so
running last gives the same guarantee. It also runs ahead of vanilla's death
message, which is why the broadcast goes through `server.execute(…)` and lands
at the end of the tick on both loaders.

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
- **`timestamp == 0` marks an imported death** — see below. No extra flag field:
  a real death cannot land on the epoch, and a flag would be one more thing that
  can disagree with the rest of the record.

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

### Why the world folder and not our own

The data belongs to the world, not to the server installation. A world restored
from backup brings its deaths back with it; a folder next to it would keep
counting deaths for events the world no longer has. Copying or renaming the
world carries the file along, and a second world gets its own counter for free.
On top of that, vanilla already encodes only what is dirty, writes off the
server thread through `Util.ioPool()` and joins on shutdown.

The price: NBT is not editable by hand, and `SavedDataStorage` writes straight
through `NbtIo.writeCompressed` — no temp-and-rename, no `.dat_old`, so a crash
mid-write can corrupt the file.

### Do not save more often than vanilla

`MinecraftServer#computeNextAutosaveInterval()` is `max(100, tickrate * 300)`,
so 6000 ticks at 20 tps: every 5 minutes. That autosave writes player data,
chunks, statistics and our `SavedData` in one pass. `SavedDataStorage#scheduleSave()`
is public, so writing after every death is one line away — do not.

A crash rolls the whole world back to the last autosave. Because everything was
written together, it rolls back together: inventory, death, and vanilla's
`minecraft:deaths` all return to the same moment. We lose deaths, but nothing
contradicts anything.

Flushing our file on its own breaks exactly that. The player gets their items
back and keeps the death, and vanilla's counter is permanently one behind ours —
which also breaks the import as a consistency check, since it can only ever add.

| | Loss on crash | State afterwards |
|---|---|---|
| autosave (now) | up to 5 min of deaths | consistent with world and statistics |
| own flush | up to the flush interval | death without its consequences, counters drift |

Saving our data together with `PlayerList#saveAll()` would at least keep the
inventory in step, but not the chunks the drops lie in. And vanilla's interval is
a private constant with no game rule, so shortening it for everything at once
would need a mixin. What actually shrinks the window is shutting down properly:
`stop` runs `saveAndJoin()` and loses nothing.

### Size

Measured on the dev world: **158 bytes** per imported death, **576 bytes** per
real one, uncompressed. The difference is the component tree of the vanilla
message, with the killer's name and its hover data.

The encode runs on the server thread once per autosave and only when something
died; gzip and the write itself do not. So the load scales with the total number
of deaths, not with the player count. 20 players at 500 deaths each is 5.8 MB
uncompressed, about 700 KB on disk — nothing.

> `ponytail:` the tighter ceiling is heap, not disk: every message stays a live
> component tree, several KB each, and is never released. If it ever hurts,
> store `message` as plain text first (~80 bytes per death, and it costs the
> per-viewer translation), and only then split into per-player files. Six-figure
> death counts, not before. Do not reach for a database.

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
  Queued with `server.execute(…)` so it stays below that line on NeoForge too,
  see above. Coordinates only in `PUBLIC` mode.

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
/deathsadmin reset <p> [confirm]   wipe counter + history                 (op)
/deathsadmin import [confirm]      backfill from vanilla statistics       (op)
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

## Import from vanilla statistics

Every world has counted `minecraft:deaths` per player since long before this mod
was installed, in `world/players/stats/<uuid>.json`. `/deathsadmin import` reads
it and adds the difference to what we recorded.

- Read through `new ServerStatsCounter(server, path)`, not by parsing the JSON:
  the constructor already handles a missing file and runs the content through the
  data fixer, so old worlds work. Online players are read from their live counter
  instead — their file is only written when the world saves.
- Names come from our own record first, then `server.services().nameToIdCache()`,
  then the bare UUID.
- The imported deaths carry `timestamp = 0`, `cause = "unknown"` and
  `Component.translatable("death.attack.generic", name)`. Dimension and position
  are filler that nothing is allowed to read.
- They are **prepended**, because they happened before anything we recorded. That
  shifts the numbers of existing deaths, which is why it is a one-shot admin
  operation and not something that runs on its own.
- `/deathsadmin import` alone only previews; `import confirm` writes. Running it
  twice imports nothing, since the counts then match — which also makes it the
  cheapest check that the two sources agree.

A vanilla counter that is *lower* than ours is ignored rather than trimmed. That
happens after `/deathsadmin reset`, and deleting a history to match a statistic
is the wrong direction.

## Pagination

Plain text, no buttons: `Page 2/7 — next: /deaths history Rambo 3`. Saves
component building and edge cases, costs one keystroke.

## Clickable teleport

Only the coordinates themselves are clickable: `ClickEvent` with `RUN_COMMAND`
pointing at `/deathsadmin tp`, `HoverEvent` showing the cause of death.

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
- Saving our data more often than the autosave looks like an improvement and is
  not, see above.
- An imported death has a placeholder position. Everything that prints or uses a
  location must check `isUnknown()` first — the rendering skips the coordinates,
  and `tp` refuses. Otherwise an operator gets flung to 0/0/0.
- Importing shifts the numbers of deaths already recorded, so a teleport link in
  an old chat message points at the wrong entry afterwards.