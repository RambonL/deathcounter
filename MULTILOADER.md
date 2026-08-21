# Multi-loader layout — Fabric + NeoForge

Implemented 2026-08-21 against NeoForge 26.2.0.64 and ModDevGradle 2.0.144.
Two jars, one source tree.

## Why it is cheap

The shared code has **no loader imports at all**. `DeathData` (SavedData,
codecs), `DeathCommands` (Brigadier, `LevelResource`, `ServerStatsCounter`),
`Config` and the scoreboard handling are plain vanilla, and MC 26.2 is Mojmap on
both loaders — the same classes compile twice with no remapping step.

What is loader-specific is one entrypoint per loader, about thirty lines each.

```
settings.gradle                                     include 'fabric', 'neoforge'
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
```

Both subprojects add `rootProject.file("src/main/java")` as a source directory.
No Architectury, no `common` module: for four event registrations a shared
`srcDir` is smaller, and it stays smaller until there are mixins, client code or
custom registries.

Jars land in `fabric/build/libs/deathcounter-fabric-<version>.jar` and
`neoforge/build/libs/deathcounter-neoforge-<version>.jar`. `base.archivesName`
is set in the root `subprojects { }` block, otherwise both would be named after
their subproject.

## The event mapping

| Fabric | NeoForge |
| --- | --- |
| `CommandRegistrationCallback` | `RegisterCommandsEvent#getDispatcher` |
| `ServerLifecycleEvents.SERVER_STARTED` | `ServerStartedEvent#getServer` |
| `ServerPlayConnectionEvents.JOIN` | `PlayerEvent.PlayerLoggedInEvent` (cast to `ServerPlayer`) |
| `ServerLivingEntityEvents.AFTER_DEATH` | `LivingDeathEvent` at `EventPriority.LOWEST` |
| `FabricLoader…getConfigDir()` | `FMLPaths.CONFIGDIR.get()` |
| `environment: "server"` | `@Mod(dist = Dist.DEDICATED_SERVER)` |

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
- **Paper/Spigot.** Different API, no shared code — a rewrite, not a port.
