package com.rambonl.deathcounter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The Fabric half of the mod: config directory and event wiring, nothing else. Everything it calls
 * lives in {@link DeathCounter} and is shared with NeoForge. See MULTILOADER.md.
 */
public class FabricEntry implements ModInitializer {
	@Override
	public void onInitialize() {
		DeathCounter.init(FabricLoader.getInstance().getConfigDir());

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> DeathCommands.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(DeathCounter::setUpObjective);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> DeathCounter.syncScore(server, handler.player));
		ServerLivingEntityEvents.AFTER_DEATH.register(DeathCounter::onDeath);
	}
}
