# Testing plan

Written 2026-08-21, alongside [MULTILOADER.md](MULTILOADER.md).

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

### Refactors this needs

Three, each smaller than the tests it unlocks:

1. `Config.init(Path configDir)` sets the config path; `load()` reads it. Tests
   point at a `@TempDir` instead of the real `config/`. This is step 1 of
   MULTILOADER.md anyway — the `FabricLoader` import moves to `DeathCounter`.
2. `Config.maySeeCoords(UUID viewer, UUID target, boolean admin)` next to the
   existing `CommandSourceStack` overload, which becomes a one-line wrapper.
   A `CommandSourceStack` cannot be built headless, a UUID can. **Still exactly
   one check** — the wrapper only unwraps the player.
3. `DeathData.add(UUID, String, Death)` next to the `ServerPlayer` overload,
   same reason.

Plus: the pure helpers in `DeathCommands` drop `private` for package-private,
and the page arithmetic in `history` moves into a `window(size, page)` method
so it can be tested without a command context.

## Level 2 — one smoke script per loader (do this with the NeoForge port)

This is the answer to "more loaders, more testing". Roughly twenty lines of
shell on top of the FIFO trick from `CLAUDE.md`:

start the server → `scoreboard objectives list` → `help deaths` → `stop` → grep
the log for exceptions, check that `config/deathcounter.json` and
`world/data/deathcounter/deaths.dat` exist.

Covers the mod loading, the entrypoint firing, command registration, the server
lifecycle hook, the config path and the SavedData directory — that is nearly the
whole loader-specific surface.

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
persistence across a restart — does not run headless. Once per loader before a
release, with `runServer` plus `runClientAlpha` (op) and `runClientBravo`:

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