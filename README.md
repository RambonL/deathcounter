# DeathCounter

A server-side mod for Fabric, NeoForge and Paper that counts player deaths and keeps
the full history: when, where, and what killed them.

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
| Minecraft | 1.20.1 |
| Java | 17 |
| Forge | 47 or newer, nothing else |

This is the 1.20.1 branch. Minecraft 26.3 on Fabric, NeoForge and Paper is on
`main`.

## Installation

1. Download the `+1.20.1` jar from
   [Modrinth](https://modrinth.com/mod/deathcounter-server). Or build it
   yourself with `./gradlew build`; it lands in `forge/build/libs/`. The
   `-sources.jar` next to it is the source code and does not belong on a server,
   and neither does anything in `forge/build/devlibs/`.
2. Drop it into the server's `mods/` folder.
3. Restart the server. It creates `config/deathcounter.json` on first start.

Server only. Players need nothing: Forge and vanilla clients without the mod
join, and the server list shows no mod mismatch. A client that has it installed
anyway simply never starts it.

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

`config/deathcounter.json` (Paper: `plugins/DeathCounter/deathcounter.json`),
created with defaults on first start:

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

Deaths live in `world/data/deathcounter.dat`, next to vanilla's own saved
data, and are written whenever the world saves. The history is never trimmed.

Inside the world folder on purpose: restore the world from a backup and the
deaths come back with it, copy the world and they follow, run a second world and
it gets its own counter.

Upgrading the world to Minecraft 26.x leaves the deaths behind for now: the
newer versions keep them in `world/data/deathcounter/deaths.dat` and do not read
this file.

A death takes about 400 bytes before the file is compressed, so twenty players
at five hundred deaths each come to roughly 4 MB of that, and a good deal less
on disk. Only what changed is encoded, and only when
somebody actually died.

The tab list number is only a display: the file is the source of truth, and the
scoreboard is rewritten from it on every join and death.

## Development

```
./gradlew build                 # the jar to ship, in forge/build/libs
./gradlew :forge:runServer      # test server in run-1.20.1/
./gradlew clean build           # rebuild from scratch
scripts/loader-test.sh          # the release check on a real Forge server
```

The mod itself lives in `src/main/java` and imports no loader at all; `forge/`
compiles that tree and adds the entrypoint. Features come from `main`, ported
to the 1.20.1 API.

The mod version and every dependency version live in `gradle.properties`, not in
`build.gradle`.

See `CLAUDE.md` for the development setup, the two prepared clients and how
changes come over from `main`, and `PLAN.md` for the design and the reasoning
behind it.

## License

Copyright (C) 2026 RambonL

GNU Lesser General Public License v3.0 only — see `LICENSE`, which incorporates
`gpl-3.0.txt` by reference.

Use it, run it, fork it. If you publish a modified version, it stays under the
LGPL and the copyright notice stays with it.
