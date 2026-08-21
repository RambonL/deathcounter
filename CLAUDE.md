# DeathCounter

Server-side mod for Fabric and NeoForge, MC 26.2. Counts player deaths with full
history and coordinates.

**Read `PLAN.md` for the design and current status before touching code.**

## Layout

One source tree, two loader projects. `src/main/java` holds the whole mod and
imports no loader at all; `fabric/` and `neoforge/` each pull that same
directory in and add an entrypoint of roughly thirty lines. New code goes in
`src/main/java` unless it is event wiring. See `MULTILOADER.md`.

## Language

Everything in this repo is English: code, comments, commit messages, docs and
player-facing messages.

## Build

```
./gradlew build                 # both jars, in fabric/build/libs and neoforge/build/libs
./gradlew test                  # headless JUnit only, seconds — see TESTING.md
./gradlew :fabric:runServer     # test server in run/
./gradlew :neoforge:runServer   # the same run/, the same world
```

Tests live in `src/test/java` in the same package as the code and reach
package-private helpers. They run in the Fabric project only — the code they
cover is the shared tree, so a second run on NeoForge would test the same
classes twice. Every test class extends `BootstrappedTest`: the registries throw
before `Bootstrap.bootStrap()`, one JVM is shared by all test classes, and a
class that fails to initialize stays failed for the whole run.

Both loaders point their run directory at `run/`, so the same world, `ops.json`
and `server.properties` serve both. Run one server at a time.

Three prepared clients with fixed usernames, each in its own run directory:
`:fabric:runClientAlpha`, `:fabric:runClientBravo`, `:fabric:runClientCharlie`.
They connect to either server — a NeoForge server with no payloads and no
registries speaks the plain vanilla protocol, which doubles as the check that
vanilla clients still get in. The dev server has `online-mode=false`, and
`run/ops.json` gives Alpha level 4.

To run several of these in parallel from the CLI, give each invocation its own
`--project-cache-dir` — otherwise they block on the Gradle project lock.

To drive the server console, start it with a FIFO on stdin, keep a writer open
so it never sees EOF, then echo commands into it:

```
mkfifo /tmp/server.fifo
sleep 86400 > /tmp/server.fifo &     # holds it open
./gradlew :fabric:runServer < /tmp/server.fifo &
echo "save-all" > /tmp/server.fifo
echo "stop"     > /tmp/server.fifo
```

Loom wires stdin through on its own; ModDevGradle does not, which is why
`neoforge/build.gradle` sets `standardInput` on `runServer` by hand. Without it
the console swallows everything, `stop` included.

**Never `pkill` the server.** It dies without saving and everything since the
last autosave is lost, which looks exactly like a persistence bug in the mod.

Java 25, use the Gradle wrapper. Versions live in `gradle.properties`, not in
`build.gradle`.

## Hard rules

- **Server-side only.** No custom packets, no custom registries, no client
  entrypoint. Vanilla clients must be able to connect — anything that does not
  travel over the vanilla protocol (scoreboard, chat, tab list, Brigadier) is
  off limits.
- **No loader imports in `src/main/java`.** That tree compiles against both
  loaders; anything Fabric- or NeoForge-specific belongs in the entrypoints, and
  a hook added to one has to be added to the other.
- **No new dependencies.** Gson ships with Minecraft, Brigadier with the
  server, persistence via vanilla `SavedData`. Fabric API is a given.
- **No mixins** as long as both loaders have an event for it.
- **Coordinates only through `maySeeCoords(source, targetUuid, admin)`.** There
  is exactly one check. Anything that prints coordinates around it leaks them.

## Names

MC 26.1 dropped obfuscation, so 26.2 ships Mojang's real names — no Yarn, no
intermediary, no remapping step. Class and method names in `PLAN.md` are still
conceptual: look them up instead of writing them from memory.

```
unzip -l ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/\
minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar | grep ServerPlayer
```

The same goes for NeoForge's own classes. Its jar is in the Gradle cache once
ModDevGradle has run, and `javap -p` on an extracted class settles a signature
faster than any wiki page:

```
unzip -l ~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/\
26.2.0.64/*/neoforge-26.2.0.64-universal.jar | grep LivingDeathEvent
```