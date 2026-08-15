# DeathCounter

A server-side Fabric mod that counts player deaths and keeps the full history:
when, where, and what killed them.

**Install it on the server only.** Players connect with an unmodified client —
everything travels over the vanilla protocol (scoreboard, chat, tab list,
commands), so there is nothing to install on their side.

## What it does

- Counts every death per player, keyed by UUID, so name changes lose nothing.
- Stores the full history: timestamp, dimension, coordinates, cause of death
  and the vanilla death message. No cap.
- Shows the total next to each name in the tab list.
- Announces each death in chat with the player's new total.
- Keeps death coordinates behind a configurable visibility setting.
- Lets operators look up any player, walk their history, and teleport to a
  death spot by clicking its coordinates.
- Takes over the deaths your world counted before the mod was installed.

## Requirements

| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 or newer |
| Fabric API | required |
| Java | 25 |

## Installation

1. Download the jar from
   [Modrinth](https://modrinth.com/mod/deathcounter-server), or build it
   yourself with `./gradlew build` — it lands in `build/libs/`. The
   `-sources.jar` next to it is the source code and does not belong on a server.
2. Drop it into the server's `mods/` folder, next to Fabric API.
3. Restart the server. It creates `config/deathcounter.json` on first start.

Server only. The mod declares itself server-side, so a client that has it
installed anyway simply never starts it.

## Commands

Everyone:

```
/deaths                            your own total and a breakdown by cause
/deaths <player>                   another player's total and breakdown
/deaths top [page]                 leaderboard, 10 per page
/deaths last [player]              a single death, newest first
/deaths history [player] [page]    full history, 10 per page, newest first
```

Operators (permission level 2 and up):

```
/deathsadmin last [player]            a single death, coordinates always shown
/deathsadmin history <player> [page]  full history, with teleport links
/deathsadmin tp <player> <number>     teleport to that death, dimension included
/deathsadmin reset <player>           show what wiping that player would cost
/deathsadmin reset <player> confirm   wipe their counter and history
/deathsadmin import                   show what could be taken over from vanilla
/deathsadmin import confirm           take it over
/deathsadmin config coords <mode>     set coordinate visibility and save it
/deathsadmin config reload            re-read the config file from disk
```

`<number>` is the number shown in front of a history entry. It counts from the
oldest death, so it does not shift when someone dies again.

In operator output the coordinates are clickable and teleport you there.

## Importing old deaths

Minecraft has always counted deaths per player, long before this mod existed.
`/deathsadmin import` reads that counter and adds whatever is missing here:

```
Import preview
  +5 Bravo
  +2 Alpha
  +7 Charlie
Run /deathsadmin import confirm to write this.
```

Those deaths carry no time, place or cause — the vanilla counter is a single
number and nothing else survived. They show up at the old end of the history:

```
#8 08-13 17:37  Charlie was shot by Skeleton  -496 89 -222
#7 ??-?? ??:??  Charlie died
```

Running it again imports nothing once the counts match, so it is also a quick
way to check that both sides agree. Note that it renumbers the deaths already
recorded, since the imported ones go in front — run it once, early.

## Configuration

`config/deathcounter.json`, created with defaults on first start:

```json
{
  "coordVisibility": "SELF"
}
```

| Mode | Death broadcast | Own coordinates | Other players' coordinates |
|---|---|---|---|
| `HIDDEN` | without coordinates | no | operators only |
| `SELF` | without coordinates | yes | operators only |
| `PUBLIC` | with coordinates | yes | anyone |

`PUBLIC` means anyone can find out where your inventory is lying. On a survival
server that is an invitation, which is why the default is `SELF`.

Change it in-game with `/deathsadmin config coords <hidden|self|public>` — that
writes the file too. Edits made by hand apply on the next server start, or
immediately with `/deathsadmin config reload`.

## Storage

Deaths live in `world/data/deathcounter/deaths.dat`, next to vanilla's own saved
data, and are written whenever the world saves. The history is never trimmed.

Inside the world folder on purpose: restore the world from a backup and the
deaths come back with it, copy the world and they follow, run a second world and
it gets its own counter.

A death costs about 576 bytes there, so twenty players at five hundred deaths
each is roughly 700 KB on disk. Only what changed is encoded, and only when
somebody actually died.

The tab list number is only a display: the file is the source of truth, and the
scoreboard is rewritten from it on every join and death.

## Development

```
./gradlew runServer     # test server in run/
./gradlew clean build   # rebuild from scratch
```

The mod version and every dependency version live in `gradle.properties`, not in
`build.gradle`.

See `CLAUDE.md` for the development setup, including the three prepared clients,
and `PLAN.md` for the design and the reasoning behind it.

## License

Copyright (C) 2026 RambonL

GNU Lesser General Public License v3.0 only — see `LICENSE`, which incorporates
`gpl-3.0.txt` by reference.

Use it, run it, fork it. If you publish a modified version, it stays under the
LGPL and the copyright notice stays with it.
