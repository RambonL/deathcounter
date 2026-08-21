# Changelog

All notable changes to DeathCounter. Versions are `<mod>+<minecraft>`, and the
Minecraft part is not a suffix to ignore: a jar built for 26.2 runs on 26.2.

## 1.1.0+26.2 — 2026-08-21

### Added

- **NeoForge support.** The mod now ships as two jars, one per loader —
  `deathcounter-fabric-…` and `deathcounter-neoforge-…`. They are not
  interchangeable. NeoForge needs 26.2.0.57 or newer and nothing else; the
  Fabric jar still wants Fabric API.

Nothing changed for existing Fabric servers. Same commands, same config, same
`world/data/deathcounter/deaths.dat` — a world can be moved from one loader to
the other and keeps every death.

### Internal

- One source tree, two loader projects. `src/main/java` holds the whole mod and
  imports no loader at all; `fabric/` and `neoforge/` compile it and add an
  entrypoint each. No Architectury, no common module. See `MULTILOADER.md`.
- The death broadcast is queued with `server.execute(…)` so it lands at the end
  of the tick. NeoForge's `LivingDeathEvent` fires ahead of vanilla's death
  message, and without this the "death #N" line would print above it.
- Build commands gained a subproject prefix: `./gradlew :fabric:runServer`,
  `./gradlew :neoforge:runServer`. `./gradlew build` builds both jars.

## 1.0.1+26.2 — 2026-08-21

Never published. Development only.

### Added

- Headless JUnit tests covering the data model, the coordinate gate, the codec
  round trip and pagination. See `TESTING.md`.

## 1.0.0+26.2 — 2026-08-15

First release.

### Added

- Counts every death per player, keyed by UUID, so name changes lose nothing.
- Full history per death: timestamp, dimension, coordinates, cause and the
  vanilla death message. No cap.
- Death total next to each name in the tab list.
- A chat line on each death with the player's new total.
- Coordinate visibility as a config setting — `HIDDEN`, `SELF` or `PUBLIC`,
  defaulting to `SELF`. Operators always see everything.
- `/deaths`, `/deaths <player>`, `/deaths top`, `/deaths last`,
  `/deaths history` for everyone; `/deathsadmin last|history|tp|reset|import|
  config` for operators.
- Clickable coordinates in operator output that teleport to the death spot,
  dimension included.
- `/deathsadmin import` takes over the deaths the world counted before the mod
  was installed, read from vanilla's own statistics.
- `/deathsadmin reset` asks before wiping a player's history.

### Notes

- Server-side only. Vanilla clients connect and see everything — the mod adds
  no packets and no registries.
- Licensed LGPL-3.0-only.
