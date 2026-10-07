# Testing plan

Written 2026-08-21, alongside [MULTILOADER.md](MULTILOADER.md), and updated the
same day when the NeoForge port landed.

## The premise

More loaders do **not** mean more tests for the logic. The shared source tree
compiles to the same Mojmap classes on Fabric and NeoForge, so everything below
`DeathCounter` is loader-neutral and only has to be tested once. What multiplies
per loader is the event wiring — about thirty lines per entrypoint.

That splits the work in two, and only the first half is worth automating with
real tests.

## Level 1 — headless JUnit (the one that pays off)

`fabric-loader-junit` runs JUnit on the loader's classpath, which is what makes
Minecraft classes usable in a plain unit test.

```gradle
dependencies { testImplementation "net.fabricmc:fabric-loader-junit:${project.loader_version}" }
test { useJUnitPlatform() }
```

They live in the Fabric subproject, which pulls `src/test/java` in the same way
the loader projects pull `src/main/java`. The code under test is the shared
tree, so running them again on the NeoForge side would test the same classes
twice.

Anything touching registries — `ComponentSerialization.CODEC` in our case —
needs Minecraft brought up first:

```java
@BeforeAll
static void bootstrap() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
}
```

No server, no client, seconds per run, fine in CI. Tests live in
`src/test/java` in the same package as the code, so they can reach
package-private helpers without anything being made public for their sake.

### What gets tested, by risk

| Subject | Why it is worth a test |
| --- | --- |
| `DeathData.CODEC` round trip | Persistence. A break here is silent until a world comes back empty. |
| `Config.maySeeCoords` | The single coordinate gate. The one place to not be lazy. |
| `Config.load` with a missing, empty or malformed file | Three fallback paths that nobody exercises by hand. |
| `DeathCommands.window` | Backwards pagination over a list stored in the other direction. |
| `DeathData.prepend` ordering | Imported deaths must land at the old end, not the new one. |
| `Death.unknown` / `isUnknown` | The `timestamp == 0` convention the import and `tp` both depend on. |
| `count` / `cause` / `time` | Pure string work, cheap to pin down. |

### Refactors this needed

Three, each smaller than the tests it unlocked:

1. `Config.init(Path configDir)` sets the config path; `load()` reads it. Tests
   point at a `@TempDir` instead of the real `config/`. This was step 1 of the
   NeoForge port anyway — the `FabricLoader` import moved to the entrypoint.
2. `Config.maySeeCoords(UUID viewer, UUID target, boolean admin)` next to the
   existing `CommandSourceStack` overload, which becomes a one-line wrapper.
   A `CommandSourceStack` cannot be built headless, a UUID can. **Still exactly
   one check** — the wrapper only unwraps the player.
3. `DeathData.add(UUID, String, Death)` next to the `ServerPlayer` overload,
   same reason.

Plus: the pure helpers in `DeathCommands` drop `private` for package-private,
and the page arithmetic in `history` moves into a `window(size, page)` method
so it can be tested without a command context.

## Level 2 — the console smoke pass, once per loader

This is the answer to "more loaders, more testing", and it is short. With the
FIFO trick from `CLAUDE.md`, start the server and feed it three commands:

```
scoreboard objectives list      # SERVER_STARTED / ServerStartedEvent fired
deaths top                      # commands registered, SavedData read
deathsadmin import              # the vanilla statistics path works
stop                            # and the console itself is reachable
```

On a world that already has deaths, both loaders must print the same numbers —
they read the same file. Then check `config/deathcounter.json` exists in `run/`
and grep the log for exceptions.

That covers mod loading, the entrypoint, command registration, the lifecycle
hook, the config path and the SavedData directory: nearly the whole
loader-specific surface, minus the death hook, which needs a player.

For a release, `scripts/loader-test.sh` does this and a great deal more against
production servers — see below. The four lines are still the quick look while
working on something.

## The universal jar — production servers, not `runServer`

`build/libs/deathcounter-<version>.jar` is never on a dev classpath: the run
tasks load the classes from the source set. Testing it means a real server per
loader with the jar in `mods/`:

- **Fabric:** the server launcher from
  `meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/<installer>/server/jar`,
  plus the Fabric API jar from the Gradle cache.
- **NeoForge:** `neoforge-<version>-installer.jar --installServer`, then
  `run.sh nogui`.

Both outside the repo, each on its own port, with `eula=true`,
`online-mode=false` and **`white-list=false`** — a fresh 26.3 server writes
`white-list=true` and turns the dev clients away. The clients need no clicking:

```
./gradlew --project-cache-dir <dir> :fabric:runClientAlpha \
    --args="--quickPlayMultiplayer localhost:<port>"
```

Everything else goes through the console FIFO. `execute as Alpha run deaths
last` runs a command with Alpha as the viewer, which is enough for the
coordinate gate; it keeps the console's permission level, so it cannot show
that a non-op is refused `/deathsadmin`. `gamerule immediate_respawn true` lets
one player die repeatedly, `damage Alpha 1000 minecraft:lava` gives causes other
than `/kill` (leave four seconds after a respawn, the invulnerability window
swallows it otherwise), and `difficulty peaceful` keeps slimes on a flat world
from adding deaths of their own.

### Pass of 2026-10-07 — Alpha and Bravo

Fabric Loader 0.19.5 with Fabric API 0.161.0+26.3, and NeoForge 26.3.0.23-beta.
Run on the jar as built at 1.1.0+26.3; the released 1.1.1+26.3 differs only in
the version string and was booted on both servers afterwards, on the worlds
this pass left behind. The same jar on both; the two console transcripts were diffed against each
other and differ only in timestamps and positions, apart from the first finding
below.

| | Fabric | NeoForge |
|---|---|---|
| Mod listed at startup, `config/deathcounter.json` created | ok | ok |
| Deaths counted and broadcast: `/kill`, lava, fall, magic, slain by Bravo | ok | ok |
| The broadcast reaches the other player's client | ok | ok |
| `deaths`, `deaths <player>` with causes, `deaths top` | ok | ok |
| `history` paging at 14 deaths; a page past the end shows the last one | ok | ok |
| Coordinates under `SELF`: own yes, others no, console no, `deathsadmin` yes | ok | ok |
| `PUBLIC`: others see them, the broadcast carries ` at x y z` | ok | ok |
| `HIDDEN`: not even one's own, `deathsadmin` still yes | ok | ok |
| Nether death carries `(the_nether)` | ok | ok |
| `deathsadmin tp` across dimensions; refused for the console and a bad number | ok | ok |
| `reset` preview and `confirm`, score back to 0 | ok | ok |
| `import` preview and `confirm`, imported deaths have no location, second run finds nothing | ok | ok |
| `config coords`, file written, `reload` after a hand edit, broken file keeps the old value | ok | ok |
| Objective `Deaths`; a falsified score is overwritten on the next death | ok | ok |
| A falsified score is overwritten on join | ok | ok |
| Lookup of a player who is offline | ok | ok |
| Restart: deaths and config survive | ok | ok |
| World written by the other loader loads with every death | ok | ok |

Findings, none of them specific to the universal jar:

- **Broadcast order on NeoForge.** For a death caused from the console, the
  "death #N" line lands *above* vanilla's death message on NeoForge and below it
  on Fabric. `DeathCounter.onDeath` defers the broadcast with `server.execute`,
  which presumably runs it on the spot when the death does not come out of a
  queued task. A player's own `/kill` is such a task, which is what the manual
  list below checks, so that case is not contradicted — but deaths from the
  server tick (mobs, the console) were not looked at separately. Open.
- **`deaths Nobody`** answers `nobody has never died.` on both loaders, for a
  name the server has never seen.

Not covered by this pass, still manual: a non-op being refused `/deathsadmin`,
the tab list column as the client draws it, clickable coordinates and tab
completion.

### Scripted since 1.2.0: `scripts/loader-test.sh`

Everything above, and the comparing, is now one command:

```
scripts/loader-test.sh            # Fabric, NeoForge and Paper side by side, then each on the others' worlds
scripts/loader-test.sh paper      # one loader, about three and a half minutes
```

It builds the jar, downloads the three servers once into
`~/.cache/deathcounter-loader-test` (versions from `gradle.properties`), starts
them on ports 25601 to 25603 with two dev clients each, sends the commands and
matches what the console prints. A line per check, `ok` or `FAIL` with the
output it got instead; the exit status is the number of failures. All three
together take five and a half minutes, six game windows and a lot of memory.

`note` lines are findings that are known and not the mod's to fix. They do not
count as failures, and they are there so nobody has to rediscover them.

Things the script had to learn, each of which looked like a mod bug first:

- **A dead player still answers to their name.** `kill Alpha` on a corpse prints
  "Killed Alpha" and nobody dies. The script asks `execute if entity
  @e[type=player,name=Alpha]` before every death — `@e` only selects the living
  — instead of sleeping and hoping.
- **`/kill` straight after a death by `/damage` can do nothing on Paper**, alive
  check or not. One retry, and only if vanilla announced no death either.
- **On Paper 26.3 build 159 a player killed in the nether never respawns** with
  `immediate_respawn` on. With no plugin installed as well; checked. The nether
  death is therefore each player's last.
- **The import needs a `save-all` first.** It finds players by their statistics
  file, which a fresh player does not have until the world saves.
- **Six Gradle clients must not build.** Each has its own project cache, so each
  would redo `compileJava` and `processResources` into the same `fabric/build`,
  and a client starting meanwhile reads half a `fabric.mod.json`. They run with
  `-x` on both.

Not in the script, because the console cannot see it: a non-op being refused
`/deathsadmin`, the tab list column as drawn, clickable coordinates and tab
completion. That is the manual list below.

### Pass of 2026-10-07 on 1.2.0+26.3 — Fabric, NeoForge, Paper

Paper 26.3 build 159 next to the two above. 211 checks, 0 failed: the 18 rows
of the table above, on all three loaders, with the same expectations.

The first Paper pass, by hand and on the jar as it was before, found two bugs
that are fixed in shared code since — see MULTILOADER.md:

- `/deathsadmin import` threw `NoSuchMethodError` on Paper.
- A restart on Paper lost every death: the load failed on the `null` data fixer
  type, and the next save wrote an empty `deaths.dat` over the old one.

Worlds across loaders, each loader booting the world another one wrote in this
pass and finding 14 and 3 deaths in it:

| written by ↓ / booted on → | Fabric | NeoForge | Paper |
|---|---|---|---|
| Fabric | | ok | ok |
| NeoForge | ok | | ok |
| Paper | does not boot | does not boot | |

A world Paper has written is in Paper's layout; Fabric and NeoForge stop with
`Failed to load datapacks` / `Overworld settings missing`. `deaths.dat` is in
the same place in all three, so this is about the world around it. The worlds
of the 1.1.1 pass, written with no fixer type, were booted on 1.2.0 as well and
kept their counts.

Notes from the run:

- **Broadcast order** on NeoForge and Paper: "death #N" above vanilla's line for
  a death caused from the console, below it on Fabric. The open finding from
  above, now with Paper on NeoForge's side.
- **Nether respawn on Paper**, as described.

## Level 3 — game tests: skipped

Fabric (`fabricApi.configureTests`, `CustomTestMethodInvoker`) and NeoForge
(`RegisterGameTestsEvent`, `DeferredRegister<Consumer<GameTestHelper>>`,
`runGameTestServer`) have entirely different registration APIs and want `.nbt`
structure templates. The harness would have to be written twice — exactly the
duplication the multi-loader layout avoids — and a real `ServerPlayer` death is
still not easy to stage. Revisit if the mod ever touches blocks or world state.

## What stays manual

The unit tests cover the data model and the rules. Everything that needs a live
player — the death hook, the scoreboard, command execution, permissions,
persistence across a restart — does not run headless.

Run the list below in full on one loader before a release. On the other, only
points 1, 6 and 12 have to be repeated: the death hook, the coordinate gate end
to end and persistence are the ones sitting on loader-specific wiring, and
everything else is the same bytecode reached through the same Brigadier tree.
Point 1 is worth the attention on NeoForge, since `LivingDeathEvent` fires at a
different moment than Fabric's `AFTER_DEATH` — the "death #N" line has to stay
*below* the vanilla death message.

With `:fabric:runServer` or `:neoforge:runServer` plus `:fabric:runClientAlpha`
(op) and `:fabric:runClientBravo`:

1. **Death, broadcast, tab list.** Bravo `/kill` → chat says `Bravo — death #1`,
   tab list shows 1. Again → #2.
2. **The display slot is not stolen.** Point the list slot at another objective,
   restart the server, check it stays there.
3. **Join sync.** Charlie logs in for the first time → 0. Bravo reconnects → 2.
4. **Reads.** `/deaths`, `/deaths Bravo`, `/deaths top`, `/deaths last`.
5. **Pagination.** Get past 10 deaths, then `/deaths history Bravo` and page 2 —
   newest first, the footer offers the next page, the last page is short.
6. **The coordinate gate, end to end.** This is the one worth doing properly,
   because the unit test stops at the UUID:
   - `SELF` (default): Bravo sees coordinates in their own history, Charlie sees
     none in `/deaths history Bravo`.
   - `/deathsadmin config coords hidden` → nobody sees any, and the broadcast
     carries none.
   - `public` → the broadcast carries them.
   - Alpha via `/deathsadmin history Bravo` → always, under every mode.
7. **Permissions.** Bravo types `/deathsadmin` → not a command, and nothing of it
   turns up in tab completion.
8. **Teleport.** `/deathsadmin tp Bravo 3` lands in the middle of the block. Die
   once in the nether so a non-overworld dimension is in the mix. The coordinates
   under `/deathsadmin history` are clickable and do the same.
9. **Reset.** `/deathsadmin reset Bravo` previews, `… confirm` wipes, and the tab
   list drops to 0 without a reconnect.
10. **Import.** `/deathsadmin import` previews, `… confirm` writes. Imported
    deaths show `??-?? ??:??`, and `tp` onto one is refused. Running it again
    reports nothing to import.
11. **Config reload.** Edit `config/deathcounter.json` by hand, then
    `/deathsadmin config reload`.
12. **Persistence.** `save-all`, `stop`, start again — counts and history intact.
    Never `pkill` the server; it dies without saving and looks exactly like a
    persistence bug.
13. **A vanilla client connects** and sees the tab list column and the chat line.
    A Fabric client against the NeoForge server counts: the mod adds no payloads
    and no registries, so what it speaks is the plain vanilla protocol.