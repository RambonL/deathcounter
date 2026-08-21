package com.rambonl.deathcounter;

import java.nio.file.Path;

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

/**
 * Everything the mod does, minus the wiring. The loader entrypoints call {@link #init} once and
 * hand the four hooks below to whatever event API they have — nothing in this package imports a
 * loader. See MULTILOADER.md.
 */
public class DeathCounter {
	public static final String MOD_ID = "deathcounter";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Scoreboard objective backing the tab list column. */
	private static final String OBJECTIVE = "deathcounter";

	/** @param configDir the loader's config directory, the one thing only the loader knows */
	public static void init(Path configDir) {
		Config.init(configDir);
	}

	public static void onDeath(LivingEntity entity, DamageSource source) {
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

		int count = data.count(player.getUUID());
		// Queued rather than sent straight away, because NeoForge's LivingDeathEvent fires at the
		// start of die() and vanilla's death message is still ahead of us. Running at the end of
		// the tick puts our line below it on both loaders.
		server.execute(() -> broadcast(server, player, count, death));
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
	public static void setUpObjective(MinecraftServer server) {
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
	public static void syncScore(MinecraftServer server, ServerPlayer player) {
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