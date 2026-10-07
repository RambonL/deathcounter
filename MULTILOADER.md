# Multi-loader layout — Fabric + NeoForge + Paper

Implemented 2026-08-21 against NeoForge 26.2.0.64 and ModDevGradle 2.0.144.
Verified on 2026-09-27 against Minecraft 26.3, NeoForge 26.3.0.23-beta and
ModDevGradle 2.0.147.
Paper added on 2026-10-07 against Paper 26.3 build 159 and paperweight-userdev
2.0.0-beta.24.
Three loader projects, one source tree, and since 2026-10-07 one jar that runs
on all of them.

## Why it is cheap

The shared code has **no loader imports at all**. `DeathData` (SavedData,
codecs), `DeathCommands` (Brigadier, `LevelResource`, `ServerStatsCounter`),
`Config` and the scoreboard handling are plain vanilla, and MC 26.3 is Mojmap on
every loader — the same classes compile three times with no remapping step.

What is loader-specific is one entrypoint per loader, thirty to sixty lines
each.

```
settings.gradle                                     include 'fabric', 'neoforge', 'paper'
build.gradle                                        subprojects { } — java, licenses, publishing
src/main/java/…                                     the mod, loader-free
src/main/resources/assets/deathcounter/icon.png     shared
src/test/java/…                                     pulled in by fabric only

fabric/build.gradle                                 Loom
fabric/src/main/java/…/FabricEntry.java
fabric/src/main/resources/fabric.mod.json

neoforge/build.gradle                               ModDevGradle
neoforge/src/main/java/…/NeoForgeEntry.java
neoforge/src/main/resources/META-INF/neoforge.mods.toml

paper/build.gradle                                  paperweight-userdev
paper/src/main/java/…/PaperEntry.java
paper/src/main/resources/paper-plugin.yml
```

All subprojects add `rootProject.file("src/main/java")` as a source directory.
No Architectury, no `common` module: for four event registrations a shared
`srcDir` is smaller, and it stays smaller until there are mixins, client code or
custom registries.

Jars land in `<loader>/build/libs/deathcounter-<loader>-<version>.jar`.
`base.archivesName` is set in the root `subprojects { }` block, otherwise they
would be named after their subproject.

`build/libs/deathcounter-<version>.jar` is all of them zipped into one, built by
`universalJar` in the root `build.gradle`, and runs on any of the loaders. That
works because nothing is remapped since 26.1: the shared classes are
byte-identical in every jar, Fabric only reads `fabric.mod.json` and loads
`FabricEntry`, NeoForge only reads `neoforge.mods.toml` and finds `@Mod` on
`NeoForgeEntry` by scanning bytecode, Paper only reads `paper-plugin.yml` and
loads `PaperEntry`. "Byte-identical" is checked, not assumed: `universalJar`
hashes every class that more than one loader jar carries and fails the build on
a mismatch, see the Paper section for why. It stops working the day one loader needs a mixin config, an access
widener or a nested jar the other chokes on. Verified on 2026-10-07 on a
production Fabric server and a production NeoForge server, not just `runServer`
— the run tasks load the classes from the source set and never see this jar.
The procedure and the results are in [TESTING.md](TESTING.md).

## The event mapping

| Fabric | NeoForge | Paper |
| --- | --- | --- |
| `CommandRegistrationCallback` | `RegisterCommandsEvent#getDispatcher` | `LifecycleEvents.COMMANDS`, `registrar().getDispatcher()` |
| `ServerLifecycleEvents.SERVER_STARTED` | `ServerStartedEvent#getServer` | `ServerLoadEvent` |
| `ServerPlayConnectionEvents.JOIN` | `PlayerEvent.PlayerLoggedInEvent` (cast to `ServerPlayer`) | `PlayerJoinEvent` (`CraftPlayer#getHandle`) |
| `ServerLivingEntityEvents.AFTER_DEATH` | `LivingDeathEvent` at `EventPriority.LOWEST` | `PlayerDeathEvent` at `MONITOR`, `ignoreCancelled` |
| `FabricLoader…getConfigDir()` | `FMLPaths.CONFIGDIR.get()` | `JavaPlugin#getDataPath()` |
| `environment: "server"` | `@Mod(dist = Dist.DEDICATED_SERVER)` | a plugin only ever runs on a server |

NeoForge listeners go on `NeoForge.EVENT_BUS`, the game bus. The mod bus only
carries loading events and none of ours are on it.

## The two traps in `LivingDeathEvent`

1. It fires *before* the death and is cancellable, so it is not the equivalent
   of `AFTER_DEATH` on its own. `EventPriority.LOWEST` plus the bus default of
   not delivering cancelled events means we only run when the death actually
   goes through.
2. It fires at the start of `die()`, so vanilla's death message comes *after*
   it and our "death #N" line would print above it. The broadcast therefore goes
   through `server.execute(…)` and lands at the end of the tick — on Fabric that
   changes nothing, since vanilla's line is already out by then.

## Paper: a plugin that does not use the plugin API

Paper used to be "a rewrite, not a port": Bukkit API on one side, obfuscated
server internals on the other. Since 26.1 the internals are Mojang's names on
Paper too, and Paper 26.3 no longer carries a plugin remapper, so a plugin can
call `net.minecraft` directly. The shared tree compiles against Paper's patched
server unchanged.

`PaperEntry` is therefore the only class that knows Bukkit exists. It takes
Bukkit's events, unwraps the vanilla objects behind them — `CraftPlayer` and
`CraftDamageSource` both have a `getHandle()` — and calls the same four methods
the other entrypoints call.

- **Paper's vanilla is not quite vanilla.** Two things compiled fine and broke
  at runtime, both found by the two-player pass and neither by `javac`:
  - `SavedDataType` with a `null` data fixer type. Vanilla dereferences it;
    Fabric API (`SavedDataStorageMixin`) and NeoForge patch that, Paper does
    not. The load threw, the data counted as absent, and the next save wrote an
    empty `deaths.dat` over the real one. Now
    `DataFixTypes.SAVED_DATA_COMMAND_STORAGE`, on every loader.
  - `PlayerList#getPlayerStats` takes a `Player` in vanilla and a `ServerPlayer`
    on Paper. Source-compatible, so each loader project compiled; but the one
    jar ships Fabric's copy of the class, and that one died with
    `NoSuchMethodError` on Paper, in `/deathsadmin import` only. Now
    `ServerPlayer#getStats`, which is the same everywhere. The hash check in
    `universalJar` exists because of this one.
- **Worlds go into Paper, not back out.** Paper loads a world written by Fabric
  or NeoForge and finds every death. On that first start it converts the world
  (`VanillaWorldMigration` in the log); afterwards `data/minecraft/` lacks
  `world_gen_settings.dat` and `game_rules.dat` among others, and Fabric and
  NeoForge refuse to boot it with `Overworld settings missing`. The same goes
  for a world Paper created itself. `deaths.dat` is
  untouched and in the same place. Nothing for the mod to fix.
- **A player killed in the nether does not respawn** on Paper 26.3 build 159
  with `immediate_respawn` on. Not ours: it happens with no plugin installed.
  The death is counted like any other.
- **This is internals, not API.** `net.minecraft` and `org.bukkit.craftbukkit`
  carry no stability promise; a Paper build may move something and the plugin
  stops loading. The fix is then a new build against the newer dev bundle,
  `paper_version` in `gradle.properties`.
- **Commands go on Paper's dispatcher through an unchecked cast.** Its type
  parameter is Paper's `CommandSourceStack` interface, the object behind it is
  the vanilla class, which implements that interface. Registered this way the
  commands carry no `deathcounter:` namespace alias and no Bukkit permission
  node — the permission level check in `DeathCommands` is the only gate, as on
  the other loaders.
- **`paper-plugin.yml`, not `plugin.yml`.** Paper only. Spigot does not read
  that file and has no `LifecycleEvents`, so it will not load the jar at all,
  which is the intended failure.
- **Not Folia.** The death broadcast goes through `MinecraftServer#execute`, and
  the scoreboard and `SavedData` are touched from whatever thread the event
  fires on. Folia has no main thread to make that safe.
- **Config lives in `plugins/DeathCounter/deathcounter.json`**, where Paper
  admins look, not in `config/`. The world data is where it is on the other
  loaders.
- **The dev bundle, not `paper-api`.** The API jar has no `net.minecraft` in it.
  paperweight-userdev downloads and patches a server on first build; that takes
  a minute once and is cached under `~/.gradle/caches/paperweight-userdev`.

## Gradle differences worth knowing

- **Loom wires stdin through, ModDevGradle does not.** Without
  `tasks.named("runServer") { standardInput = System.in }` the NeoForge console
  ignores everything typed at it, `stop` included, and the only way out is a
  signal. Costs a world save if you find out the hard way.
- Both run configurations point at `../run`, so the dev world, `ops.json` and
  `server.properties` are shared and either loader can boot the same save.
- No client run on the NeoForge side: the mod is dedicated-server only, so a
  NeoForge client would not load it anyway. `:fabric:runClientAlpha` and friends
  connect to both servers.
- `logoFile` in `neoforge.mods.toml` is deprecated in 26.2 — it is `iconFile`
  now. `displayTest` is gone entirely; a mod with no payloads and no registries
  accepts vanilla clients without saying so.

## Deliberately out of scope

- **Architectury / a common module.** See above.
- **Quilt.** Loads `fabric.mod.json` as is, nothing to do.
- **Spigot, Folia.** See the Paper section above.
