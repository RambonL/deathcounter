package com.rambonl.deathcounter;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import net.minecraft.server.level.ServerPlayer;

/**
 * The NeoForge half of the mod: config directory and event wiring, nothing else. Everything it
 * calls lives in {@link DeathCounter} and is shared with Fabric. See MULTILOADER.md.
 *
 * <p>{@code DEDICATED_SERVER} mirrors {@code "environment": "server"} in fabric.mod.json, so the
 * mod behaves the same on both loaders. The mod registers no payloads and no registries, so a
 * NeoForge server still accepts vanilla clients.
 */
@Mod(value = DeathCounter.MOD_ID, dist = Dist.DEDICATED_SERVER)
public class NeoForgeEntry {
	public NeoForgeEntry() {
		DeathCounter.init(FMLPaths.CONFIGDIR.get());

		// The game bus. The mod bus only carries loading events, and none of ours are on it.
		IEventBus bus = NeoForge.EVENT_BUS;

		bus.addListener(RegisterCommandsEvent.class,
				event -> DeathCommands.register(event.getDispatcher()));

		bus.addListener(ServerStartedEvent.class,
				event -> DeathCounter.setUpObjective(event.getServer()));

		bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
			if (event.getEntity() instanceof ServerPlayer player) {
				DeathCounter.syncScore(player.level().getServer(), player);
			}
		});

		// LivingDeathEvent fires before the death and can be cancelled. LOWEST puts us last, and
		// listeners do not receive cancelled events by default, so we only run when the death
		// really goes through — the same guarantee Fabric's AFTER_DEATH gives.
		bus.addListener(EventPriority.LOWEST, LivingDeathEvent.class,
				event -> DeathCounter.onDeath(event.getEntity(), event.getSource()));
	}
}
