# Multi-loader plan — Fabric + NeoForge

Not implemented yet. Written 2026-08-21 against NeoForge 26.2.0.64 (stable) and
ModDevGradle 2.0.144.

## Why this is cheap

After step 1 the shared code has **no loader imports at all**. Only two places
are loader-specific today:

- `DeathCounter.java:3-7` — four Fabric imports, four event registrations
- `Config.java:33` — `FabricLoader.getInstance().getConfigDir()`

`DeathData` (SavedData, codecs), `DeathCommands` (Brigadier, `LevelResource`,
`ServerStatsCounter`) and the scoreboard handling are plain vanilla. MC 26.2 is
Mojmap on both loaders, so those classes compile unchanged for both.

## Step 1 — pull the loader out of the core

`Config.java`: the config path stops being a constant.

```java
private static Path file;                       // was: static final FILE via FabricLoader

public static void load(Path configDir) {
    file = configDir.resolve(DeathCounter.MOD_ID + ".json");
    ...
}
```

`save()` uses `file`. The Fabric import goes.

`DeathCounter.java`: drop `implements ModInitializer` and the four imports.
`onInitialize()` becomes

```java
public static void init(Path configDir) { Config.load(configDir); }
```

and `setUpObjective`, `syncScore` and `onDeath` widen from `private static` to
`public static`. Wiring the events becomes each loader's job.

## Step 2 — Gradle: two subprojects, one source tree

No Architectury, no `common` module. Both loader projects pull in the same
source directory:

```
settings.gradle              include 'fabric', 'neoforge'
src/main/java/…              shared, stays where it is
src/main/resources/assets/   shared icon.png
fabric/build.gradle          Loom  + sourceSets.main.java.srcDir "../src/main/java"
fabric/src/main/java/…/FabricEntry.java
fabric/src/main/resources/fabric.mod.json          (moved)
neoforge/build.gradle        ModDevGradle 2.0.144 + the same srcDir
neoforge/src/main/java/…/NeoForgeEntry.java
neoforge/src/main/resources/META-INF/neoforge.mods.toml
```

The root `build.gradle` keeps Java 25, the `jar` license block, `withSourcesJar`
and `publishing` in a `subprojects { }`. `settings.gradle` additionally needs the
NeoForged maven in `pluginManagement`.

**Point `runDir` at `../run`** (and `../run/alpha|bravo|charlie`), otherwise the
dev world and `run/ops.json` are orphaned.

## Step 3 — entrypoints

`FabricEntry`: the four registrations as they are today, with
`DeathCounter.init(FabricLoader.getInstance().getConfigDir())`.

`NeoForgeEntry`: `@Mod("deathcounter")`, everything on `NeoForge.EVENT_BUS` (the
game bus, not the mod bus).

| Fabric | NeoForge |
| --- | --- |
| `CommandRegistrationCallback` | `RegisterCommandsEvent#getDispatcher` |
| `ServerLifecycleEvents.SERVER_STARTED` | `ServerStartedEvent#getServer` |
| `ServerPlayConnectionEvents.JOIN` | `PlayerEvent.PlayerLoggedInEvent` (cast to `ServerPlayer`) |
| `ServerLivingEntityEvents.AFTER_DEATH` | `LivingDeathEvent` at `EventPriority.LOWEST` |
| `FabricLoader…getConfigDir()` | `FMLPaths.CONFIGDIR.get()` |

Two traps with `LivingDeathEvent`:

1. It fires *before* the death and is cancellable. `LOWEST` plus NeoForge's
   default of skipping remaining listeners on a cancelled event means we only
   run when the death actually goes through — same guarantee as Fabric's
   `AFTER_DEATH`.
2. It fires at the start of `die()`, so vanilla's death message comes later and
   our "death #N" line would print before it. Wrap the broadcast in
   `server.execute(…)` so it lands at the end of the tick and the order matches
   Fabric again.

## Step 4 — `neoforge.mods.toml`

`modLoader="javafml"`, `license="LGPL-3.0-only"`, dependencies on `neoforge` and
on `minecraft` `[26.2,26.3)`. Server side: `side="SERVER"` on the Minecraft
dependency, and a `displayTest` that does not reject vanilla clients. The mod
registers no payloads and no registries, so a NeoForge server accepts vanilla
clients anyway — the hard rule stays intact.

## Step 5 — verify and document

- `./gradlew :fabric:runServer` and `:neoforge:runServer`, vanilla client on
  each: death → chat line, tab list, `/deaths`, restart → data still there.
- **Look the NeoForge class names up in the actual jar**, do not write them from
  memory — same `unzip -l | grep` trick as for Minecraft, once ModDevGradle has
  downloaded it.
- `PLAN.md`, `README.md`, `CLAUDE.md`, `MODRINTH.md`: "Fabric mod" becomes
  "Fabric/NeoForge", build commands get the subproject prefix. The Modrinth
  version gets `neoforge` as a second loader.

## Cost

Roughly 80 new lines, 15 changed.

## Deliberately out of scope

- **Architectury / a common module.** Worth it once there are mixins, client
  code or custom registries. For four event registrations a shared `srcDir` is
  smaller.
- **Quilt.** Loads `fabric.mod.json` as is, nothing to do.
- **Paper/Spigot.** Different API, no shared code — a rewrite, not a port.