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

Not scripted yet. It is four lines into a FIFO and the numbers have to be read
by a human anyway, so a script would mostly be a wrapper around `grep`.

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