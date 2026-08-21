# DeathCounter

Server-side Fabric mod, MC 26.2. Counts player deaths with full history and
coordinates.

**Read `PLAN.md` for the design and current status before touching code.**

## Language

Everything in this repo is English: code, comments, commit messages, docs and
player-facing messages.

## Build

```
./gradlew build       # jar lands in build/libs/, runs the tests
./gradlew test        # headless JUnit only, seconds — see TESTING.md
./gradlew runServer   # test server in run/
```

Tests live in `src/test/java` in the same package as the code and reach
package-private helpers. Every test class extends `BootstrappedTest`: the
registries throw before `Bootstrap.bootStrap()`, one JVM is shared by all test
classes, and a class that fails to initialize stays failed for the whole run.

Three prepared clients with fixed usernames, each in its own run directory:
`runClientAlpha`, `runClientBravo`, `runClientCharlie`. The dev server has
`online-mode=false`, and `run/ops.json` gives Alpha level 4.

To run several of these in parallel from the CLI, give each invocation its own
`--project-cache-dir` — otherwise they block on the Gradle project lock.

To drive the server console, start it with a FIFO on stdin, keep a writer open
so it never sees EOF, then echo commands into it:

```
mkfifo /tmp/server.fifo
sleep 86400 > /tmp/server.fifo &     # holds it open
./gradlew runServer < /tmp/server.fifo &
echo "save-all" > /tmp/server.fifo
echo "stop"     > /tmp/server.fifo
```

**Never `pkill` the server.** It dies without saving and everything since the
last autosave is lost, which looks exactly like a persistence bug in the mod.

Java 25, use the Gradle wrapper. Versions live in `gradle.properties`, not in
`build.gradle`.

## Hard rules

- **Server-side only.** No custom packets, no custom registries, no client
  entrypoint. Vanilla clients must be able to connect — anything that does not
  travel over the vanilla protocol (scoreboard, chat, tab list, Brigadier) is
  off limits.
- **No new dependencies.** Gson ships with Minecraft, Brigadier with the
  server, persistence via vanilla `SavedData`. Fabric API is a given.
- **No mixins** as long as Fabric API has an event for it.
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