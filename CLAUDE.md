# DeathCounter — Minecraft 1.20.1 branch

Server-side mod for Forge, MC 1.20.1. Counts player deaths with full history and
coordinates.

**This is the `mc/1.20.1` branch,** a port of `main` (MC 26.3 on Fabric, NeoForge
and Paper) for the modpacks that still run 1.20.1 Forge. Features are written on
`main` first and brought over with `git cherry-pick` — see "Porting from main".

**Read `PLAN.md` for the design before touching code.** `PLAN.md`,
`MULTILOADER.md` and `TESTING.md` describe `main`: the design holds here, the
loader wiring and the test setup do not. This file says what differs.

## Layout

The same idea as `main`, with one loader. `src/main/java` holds the whole mod
and imports no loader at all; `forge/` pulls that directory in and adds
`ForgeEntry` (the event wiring) and `META-INF/mods.toml`. New code goes in
`src/main/java` unless it is event wiring. A Fabric 1.20.1 port would be a
`fabric/` project on the same tree, with Loom and `officialMojangMappings()`.

## Language

Everything in this repo is English: code, comments, commit messages, docs and
player-facing messages.

## Build

```
./gradlew build                    # forge/build/libs/deathcounter-forge-<version>.jar
./gradlew :forge:runServer         # test server in run-1.20.1/
./gradlew :forge:runClientAlpha    # Forge client without the mod, username Alpha (op)
./gradlew :forge:runClientBravo    # the same as Bravo
scripts/loader-test.sh             # the release check: a production Forge server, two
                                   # clients, every feature from the console, ~5 min
```

**1.20.1 is obfuscated.** The source is written against Mojang's names, and
`reobfJar` rewrites the jar to the SRG names (`m_123341_`) Forge runs on.
`forge/build/libs` holds that reobfuscated jar — the one to ship.
`forge/build/devlibs` holds the plain one, which only runs in the dev
environment.

Java 17 through the Gradle toolchain (`/usr/lib/jvm/java-17-openjdk`); Gradle
itself runs on any newer JDK. Use the wrapper. Versions live in
`gradle.properties`, not in `build.gradle`.

The dev server lives in `run-1.20.1/`, not `run/`: `run/` holds `main`'s 26.3
world, and a 1.20.1 server cannot load a newer world. It has
`online-mode=false`, and `run-1.20.1/ops.json` gives Alpha level 4.

The two clients run with `loadedMods = []` — Forge without this mod, the way a
modpack player's game is when only the server has it. That they get in is the
check that the mod stays server-side. `-Pjoin=localhost:25565` sends them
straight onto a server. Do not pass `--args` to a run task: it replaces the
arguments ModDevGradle sets.

To run several Gradle invocations in parallel from the CLI, give each its own
`--project-cache-dir` — otherwise they block on the Gradle project lock.

To drive the server console, start it with a FIFO on stdin, keep a writer open
so it never sees EOF, then echo commands into it. `forge/build.gradle` sets
`standardInput` on `runServer`; ModDevGradle does not wire it through itself.

```
mkfifo /tmp/server.fifo
sleep 86400 > /tmp/server.fifo &     # holds it open
./gradlew :forge:runServer < /tmp/server.fifo &
echo "save-all" > /tmp/server.fifo
echo "stop"     > /tmp/server.fifo
```

**Never `pkill` the server.** It dies without saving and everything since the
last autosave is lost, which looks exactly like a persistence bug in the mod.

No JUnit on this branch. The tests live on `main` and cover the shared logic.
What differs here is the vanilla API underneath, and only a real server
exercises that — `scripts/loader-test.sh`, plus the client-side points it cannot
see: the tab list column, clickable coordinates, tab completion, and a non-op
being refused `/deathsadmin`.

## Hard rules

- **Server-side only.** No custom packets, no network channels, no custom
  registries. Forge and vanilla clients without the mod must be able to
  connect — anything that does not travel over the vanilla protocol
  (scoreboard, chat, tab list, Brigadier) is off limits.
- **No loader imports in `src/main/java`.** Anything Forge-specific belongs in
  `ForgeEntry`.
- **No new dependencies.** Gson ships with Minecraft, Brigadier with the
  server, persistence via vanilla `SavedData`.
- **No mixins** as long as Forge has an event for it.
- **Coordinates only through `maySeeCoords(source, targetUuid, admin)`.** There
  is exactly one check. Anything that prints coordinates around it leaks them.
- **Java 17.** `List.getLast()`, `Math.clamp` and the rest of 21 do not exist
  here; the compiler says so.

## Porting from main

`git cherry-pick <commit>` from `main`. Changes under `fabric/`, `neoforge/`,
`paper/` or `src/test/` do not apply here. Conflicts land where the vanilla API
differs between 26.3 and 1.20.1:

| 26.3 (`main`) | 1.20.1 (here) |
|---|---|
| `SavedDataType`, `server.getDataStorage()`, `world/data/deathcounter/deaths.dat` | `server.overworld().getDataStorage().computeIfAbsent(load, create, "deathcounter")`, `save(CompoundTag)`, `world/data/deathcounter.dat` |
| `ComponentSerialization.CODEC` | `Codec.STRING.xmap(Component.Serializer::fromJson, ::toJson)` — not `ExtraCodecs.COMPONENT`, whose JSON-to-NBT lists break on mixed types |
| `Identifier`, `ResourceKey#identifier()` | `ResourceLocation`, `ResourceKey#location()` |
| `DisplaySlot.LIST` | `Scoreboard.DISPLAY_SLOT_LIST` |
| `addObjective(…, displayAutoUpdate, numberFormat)` | `addObjective(name, criteria, title, renderType)` |
| `getOrCreatePlayerScore(holder, obj).set(n)` | `getOrCreatePlayerScore(name, obj).setScore(n)` |
| `Commands.hasPermission(LEVEL_GAMEMASTERS)` | `source -> source.hasPermission(LEVEL_GAMEMASTERS)` |
| `NameAndId`, `services().nameToIdCache()` | `GameProfile`, `getProfileCache()` |
| `ClickEvent.RunCommand`, `HoverEvent.ShowText` | `new ClickEvent(Action.RUN_COMMAND, …)`, `new HoverEvent(Action.SHOW_TEXT, …)` |
| `teleportTo(level, x, y, z, Set.of(), yaw, pitch, true)` | `teleportTo(level, x, y, z, yaw, pitch)` |
| `new ServerStatsCounter(server, Path)` | `new ServerStatsCounter(server, File)` |

Forge's event bus here has no `addListener(Class, Consumer)`: listeners are
typed lambdas, `addListener((LivingDeathEvent e) -> …)`.

Each release is `<mod>+1.20.1`, gets its own entry in `CHANGELOG.md` and its own
tag (`v1.2.0+1.20.1`).

## Names

Look names up instead of writing them from memory. Once ModDevGradle has run,
the Mojang-named Minecraft and Forge classes are in one jar:

```
javap -cp forge/build/moddev/artifacts/forge-1.20.1-47.4.23.jar \
    net.minecraft.world.scores.Scoreboard
```
