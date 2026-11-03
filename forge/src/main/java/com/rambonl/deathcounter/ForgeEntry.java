package com.rambonl.deathcounter;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import net.minecraft.server.level.ServerPlayer;

/**
 * The Forge half of the mod: config directory and event wiring, nothing else. Everything it
 * calls lives in {@link DeathCounter}. See CLAUDE.md.
 *
 * <p>The mod registers no network channels, so a Forge client without it connects as usual, and
 * {@code displayTest} in mods.toml keeps the server list from flagging the missing mod.
 */
@Mod(DeathCounter.MOD_ID)
public class ForgeEntry {
	public ForgeEntry() {
		// Forge's @Mod has no dist, unlike NeoForge's. Bailing out here does what "environment":
		// "server" does on Fabric: on a client or in singleplayer the mod loads and does nothing.
		if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
			return;
		}

		DeathCounter.init(FMLPaths.CONFIGDIR.get());

		// The game bus. The mod bus only carries loading events, and none of ours are on it. This
		// bus has no addListener(Class, Consumer): the event type comes from the lambda parameter.
		IEventBus bus = MinecraftForge.EVENT_BUS;

		bus.addListener((RegisterCommandsEvent event) -> DeathCommands.register(event.getDispatcher()));

		bus.addListener((ServerStartedEvent event) -> DeathCounter.setUpObjective(event.getServer()));

		bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
			if (event.getEntity() instanceof ServerPlayer player) {
				DeathCounter.syncScore(player.level().getServer(), player);
			}
		});

		// LivingDeathEvent fires before the death and can be cancelled. LOWEST puts us last, and
		// listeners do not receive cancelled events by default, so we only run when the death
		// really goes through.
		bus.addListener(EventPriority.LOWEST,
				(LivingDeathEvent event) -> DeathCounter.onDeath(event.getEntity(), event.getSource()));
	}
}
