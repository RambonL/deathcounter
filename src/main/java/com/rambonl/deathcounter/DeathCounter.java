package com.rambonl.deathcounter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeathCounter implements ModInitializer {
	public static final String MOD_ID = "deathcounter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Scoreboard objective backing the tab list column. */
	private static final String OBJECTIVE = "deathcounter";

	@Override
	public void onInitialize() {
		Config.load();

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> DeathCommands.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(DeathCounter::setUpObjective);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncScore(server, handler.player));
		ServerLivingEntityEvents.AFTER_DEATH.register(DeathCounter::onDeath);
	}

	private static void onDeath(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer player)) {
			return;
		}

		MinecraftServer server = player.level().getServer();
		DeathData data = DeathData.get(server);

		DeathData.Death death = new DeathData.Death(
				System.currentTimeMillis(),
				player.level().dimension(),
				player.blockPosition(),
				source.getMsgId(),
				source.getLocalizedDeathMessage(player));

		data.add(player, death);
		syncScore(server, player);
		broadcast(server, player, data.count(player.getUUID()), death);
	}

	/**
	 * Announces the new total. Vanilla already printed why the player died and replacing that
	 * line would need a mixin, so this is a second, short one.
	 */
	private static void broadcast(MinecraftServer server, ServerPlayer player, int count, DeathData.Death death) {
		MutableComponent message = Component.literal(player.getScoreboardName())
				.append(Component.literal(" — death #" + count).withStyle(ChatFormatting.GRAY));

		if (Config.showCoordsInBroadcast()) {
			message.append(Component.literal(" at " + posText(death)).withStyle(ChatFormatting.DARK_GRAY));
		}

		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	/** Dimension only when it is not the overworld, since that is where most deaths happen. */
	static String posText(DeathData.Death death) {
		String pos = death.pos().getX() + " " + death.pos().getY() + " " + death.pos().getZ();
		return death.dimension() == Level.OVERWORLD
				? pos
				: pos + " (" + death.dimension().identifier().getPath() + ")";
	}

	/** Creates the objective, and claims the tab list slot only if nothing else already holds it. */
	private static void setUpObjective(MinecraftServer server) {
		ServerScoreboard scoreboard = server.getScoreboard();
		Objective objective = scoreboard.getObjective(OBJECTIVE);

		if (objective == null) {
			objective = scoreboard.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, Component.literal("Deaths"),
					ObjectiveCriteria.RenderType.INTEGER, true, null);
		}

		if (scoreboard.getDisplayObjective(DisplaySlot.LIST) == null) {
			scoreboard.setDisplayObjective(DisplaySlot.LIST, objective);
		}
	}

	/**
	 * Writes our count into the scoreboard. Our file is the truth, the scoreboard is only the
	 * display, so anything edited there is overwritten on the next join or death.
	 */
	static void syncScore(MinecraftServer server, ServerPlayer player) {
		Objective objective = server.getScoreboard().getObjective(OBJECTIVE);

		if (objective != null) {
			server.getScoreboard()
					.getOrCreatePlayerScore(player, objective)
					.set(DeathData.get(server).count(player.getUUID()));
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}