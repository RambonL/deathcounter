package com.rambonl.deathcounter;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@code /deaths} command tree.
 *
 * <p>Every subtree is built by a method taking an {@code admin} flag and registered twice: once
 * under {@code /deaths}, once under {@code /deathsadmin}. The flag travels down to
 * {@link Config#maySeeCoords}, so there is one implementation per command and one place where
 * coordinates are released.
 *
 * <p>The admin half is a separate root command rather than a {@code /deaths admin} subcommand on
 * purpose. {@code CommandDispatcher#getCompletionSuggestions} does not apply the {@code requires}
 * predicate — only parsing and the tree sent to clients do. A literal sitting next to an argument
 * that asks the server for suggestions therefore gets suggested to everyone, operator or not.
 * Keeping the two apart means no such sibling exists.
 */
public final class DeathCommands {
	private static final int PAGE_SIZE = 10;

	private static final String ADMIN = "deathsadmin";

	// No year: chat is narrow, and a death from a year ago is not told apart by its timestamp.
	private static final DateTimeFormatter TIME = DateTimeFormatter
			.ofPattern("MM-dd HH:mm")
			.withZone(ZoneId.systemDefault());

	private static final SimpleCommandExceptionType ERROR_ONE_PLAYER =
			new SimpleCommandExceptionType(Component.literal("Expected exactly one player"));

	/** Death numbers are 1-based and count from the oldest, so they survive later deaths. */
	private record Target(UUID uuid, String name) {
	}

	private DeathCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("deaths")
				.executes(ctx -> stats(ctx, self(ctx)))
				.then(playerArgument().executes(ctx -> stats(ctx, target(ctx))))
				.then(topNode())
				.then(lastNode(false))
				.then(historyNode(false)));

		dispatcher.register(adminRoot());
	}

	// --- tree ---------------------------------------------------------------------------------

	private static RequiredArgumentBuilder<CommandSourceStack, GameProfileArgument.Result> playerArgument() {
		// The argument type only suggests online players, which would defeat looking up someone
		// who has not logged in for months. Suggest everyone we have a record of instead.
		return Commands.argument("player", GameProfileArgument.gameProfile()).suggests(DeathCommands::suggestTracked);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> topNode() {
		return Commands.literal("top")
				.executes(ctx -> top(ctx, 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> top(ctx, IntegerArgumentType.getInteger(ctx, "page"))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> lastNode(boolean admin) {
		return Commands.literal("last")
				.executes(ctx -> last(ctx, self(ctx), admin))
				.then(playerArgument().executes(ctx -> last(ctx, target(ctx), admin)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> historyNode(boolean admin) {
		return Commands.literal("history")
				.executes(ctx -> history(ctx, self(ctx), 1, admin))
				.then(playerArgument()
						.executes(ctx -> history(ctx, target(ctx), 1, admin))
						.then(Commands.argument("page", IntegerArgumentType.integer(1))
								.executes(ctx -> history(ctx, target(ctx),
										IntegerArgumentType.getInteger(ctx, "page"), admin))));
	}

	/** Same reads with the visibility check lifted, plus the tools only an operator needs. */
	private static LiteralArgumentBuilder<CommandSourceStack> adminRoot() {
		return Commands.literal(ADMIN)
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(lastNode(true))
				.then(historyNode(true))
				.then(Commands.literal("tp")
						.then(playerArgument()
								.then(Commands.argument("number", IntegerArgumentType.integer(1))
										.executes(ctx -> teleport(ctx, target(ctx),
												IntegerArgumentType.getInteger(ctx, "number"))))))
				.then(Commands.literal("reset")
						.then(playerArgument()
								.executes(ctx -> reset(ctx, target(ctx), false))
								.then(Commands.literal("confirm")
										.executes(ctx -> reset(ctx, target(ctx), true)))))
				.then(Commands.literal("import")
						.executes(ctx -> importDeaths(ctx, false))
						.then(Commands.literal("confirm").executes(ctx -> importDeaths(ctx, true))))
				.then(configNode());
	}

	private static LiteralArgumentBuilder<CommandSourceStack> configNode() {
		LiteralArgumentBuilder<CommandSourceStack> coords = Commands.literal("coords")
				.executes(ctx -> reportVisibility(ctx, "coordVisibility = ", false));

		// One literal per mode: free tab completion, and no way to mistype it into a parse error.
		for (Config.CoordVisibility value : Config.CoordVisibility.values()) {
			coords.then(Commands.literal(value.name().toLowerCase(Locale.ROOT))
					.executes(ctx -> setVisibility(ctx, value)));
		}

		return Commands.literal("config")
				.then(coords)
				.then(Commands.literal("reload").executes(DeathCommands::reload));
	}

	// --- commands -----------------------------------------------------------------------------

	private static int stats(CommandContext<CommandSourceStack> ctx, Target target) {
		List<DeathData.Death> deaths = deaths(ctx, target);

		if (deaths.isEmpty()) {
			return reportNoDeaths(ctx, target);
		}

		MutableComponent message = header(target.name() + " — " + count(deaths.size()));

		deaths.stream()
				.collect(Collectors.groupingBy(DeathData.Death::causeId, Collectors.counting()))
				.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.forEach(cause -> message
						.append(Component.literal("\n  " + cause.getValue() + "× ").withStyle(ChatFormatting.GOLD))
						.append(Component.literal(cause(cause.getKey())).withStyle(ChatFormatting.WHITE)));

		ctx.getSource().sendSuccess(() -> message, false);
		return deaths.size();
	}

	private static int last(CommandContext<CommandSourceStack> ctx, Target target, boolean admin) {
		List<DeathData.Death> deaths = deaths(ctx, target);

		if (deaths.isEmpty()) {
			return reportNoDeaths(ctx, target);
		}

		// No header: the vanilla death message already names the player.
		MutableComponent message = entry(ctx.getSource(), target, deaths.getLast(), deaths.size(), admin);

		ctx.getSource().sendSuccess(() -> message, false);
		return 1;
	}

	private static int history(CommandContext<CommandSourceStack> ctx, Target target, int page, boolean admin) {
		List<DeathData.Death> deaths = deaths(ctx, target);

		if (deaths.isEmpty()) {
			return reportNoDeaths(ctx, target);
		}

		int pages = pageCount(deaths.size());
		int current = Math.clamp(page, 1, pages);

		// Stored oldest first, shown newest first: walk one page backwards from the end.
		int end = deaths.size() - (current - 1) * PAGE_SIZE;
		int start = Math.max(0, end - PAGE_SIZE);

		MutableComponent message = header(target.name() + " — history, newest first");

		for (int i = end - 1; i >= start; i--) {
			message.append(Component.literal("\n"))
					.append(entry(ctx.getSource(), target, deaths.get(i), i + 1, admin));
		}

		if (pages > 1) {
			message.append(footer(current, pages, command(admin, "history " + target.name() + " " + (current + 1))));
		}

		ctx.getSource().sendSuccess(() -> message, false);
		return end - start;
	}

	private static int top(CommandContext<CommandSourceStack> ctx, int page) {
		DeathData data = DeathData.get(ctx.getSource().getServer());

		List<UUID> ranked = data.tracked().stream()
				.sorted(Comparator.comparingInt(data::count).reversed())
				.toList();

		if (ranked.isEmpty()) {
			ctx.getSource().sendSuccess(
					() -> Component.literal("Nobody has died yet.").withStyle(ChatFormatting.GRAY), false);
			return 0;
		}

		int pages = pageCount(ranked.size());
		int current = Math.clamp(page, 1, pages);
		int start = (current - 1) * PAGE_SIZE;
		int end = Math.min(ranked.size(), start + PAGE_SIZE);

		MutableComponent message = header("Most deaths");

		for (int i = start; i < end; i++) {
			UUID player = ranked.get(i);
			message.append(Component.literal("\n" + (i + 1) + ". ").withStyle(ChatFormatting.GRAY))
					.append(Component.literal(data.name(player)).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(" — " + count(data.count(player))).withStyle(ChatFormatting.GOLD));
		}

		if (pages > 1) {
			message.append(footer(current, pages, "/deaths top " + (current + 1)));
		}

		ctx.getSource().sendSuccess(() -> message, false);
		return end - start;
	}

	// --- admin tools --------------------------------------------------------------------------

	private static int teleport(CommandContext<CommandSourceStack> ctx, Target target, int number)
			throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		List<DeathData.Death> deaths = deaths(ctx, target);

		if (number > deaths.size()) {
			ctx.getSource().sendFailure(
					Component.literal(target.name() + " has only " + deaths.size() + " deaths"));
			return 0;
		}

		DeathData.Death death = deaths.get(number - 1);

		if (death.isUnknown()) {
			ctx.getSource().sendFailure(
					Component.literal("Death #" + number + " was imported and has no location"));
			return 0;
		}

		ServerLevel level = ctx.getSource().getServer().getLevel(death.dimension());

		// The dimension can be gone if a datapack that added it was removed.
		if (level == null) {
			ctx.getSource().sendFailure(
					Component.literal("Dimension " + death.dimension().identifier() + " no longer exists"));
			return 0;
		}

		// Half a block on X and Z lands in the middle rather than on the corner.
		player.teleportTo(level, death.pos().getX() + 0.5, death.pos().getY(), death.pos().getZ() + 0.5,
				Set.of(), player.getYRot(), player.getXRot(), true);

		ctx.getSource().sendSuccess(() -> Component
				.literal("Teleported to death #" + number + " of " + target.name())
				.withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	/**
	 * @param apply when false, only says what would go — an uncapped history is wiped by one
	 *              tab completion, and nothing brings it back
	 */
	private static int reset(CommandContext<CommandSourceStack> ctx, Target target, boolean apply) {
		MinecraftServer server = ctx.getSource().getServer();
		DeathData data = DeathData.get(server);
		int had = data.count(target.uuid());

		if (had == 0) {
			return reportNoDeaths(ctx, target);
		}

		if (!apply) {
			ctx.getSource().sendSuccess(() -> Component
					.literal("Would wipe " + count(had) + " of " + target.name() + ". Run /"
							+ ADMIN + " reset " + target.name() + " confirm to do it.")
					.withStyle(ChatFormatting.GRAY), false);
			return had;
		}

		data.reset(target.uuid());

		// Without this the tab list keeps the old number until they next die or reconnect.
		ServerPlayer online = server.getPlayerList().getPlayer(target.uuid());

		if (online != null) {
			DeathCounter.syncScore(server, online);
		}

		ctx.getSource().sendSuccess(() -> Component
				.literal("Wiped " + had + " deaths of " + target.name())
				.withStyle(ChatFormatting.GRAY), true);
		return had;
	}

	/**
	 * Backfills from vanilla's own death counter, which the world has kept since long before this mod
	 * was installed. That counter is a single number, so the difference to what we recorded is added
	 * as deaths with no time, place or cause. Running it again imports nothing, since the counts then
	 * match.
	 *
	 * @param apply when false, only lists what would change — nobody should write into a history blind
	 */
	private static int importDeaths(CommandContext<CommandSourceStack> ctx, boolean apply) {
		MinecraftServer server = ctx.getSource().getServer();
		DeathData data = DeathData.get(server);
		Path stats = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);

		MutableComponent message = header(apply ? "Imported from vanilla statistics" : "Import preview");
		int total = 0;

		for (UUID uuid : trackedByVanilla(stats)) {
			int missing = vanillaDeaths(server, stats, uuid) - data.count(uuid);

			// Negative when someone was reset here but not in vanilla. Adding nothing is the safe read.
			if (missing <= 0) {
				continue;
			}

			String name = name(server, data, uuid);
			total += missing;

			if (apply) {
				// Death is immutable, so one instance can stand in for all of them.
				data.prepend(uuid, name, Collections.nCopies(missing, DeathData.Death.unknown(name)));

				ServerPlayer online = server.getPlayerList().getPlayer(uuid);

				if (online != null) {
					DeathCounter.syncScore(server, online);
				}
			}

			message.append(Component.literal("\n  +" + missing + " ").withStyle(ChatFormatting.GOLD))
					.append(Component.literal(name).withStyle(ChatFormatting.WHITE));
		}

		if (total == 0) {
			ctx.getSource().sendSuccess(() -> Component
					.literal("Nothing to import — every counter already matches vanilla.")
					.withStyle(ChatFormatting.GRAY), false);
			return 0;
		}

		if (!apply) {
			message.append(Component.literal("\nRun /" + ADMIN + " import confirm to write this.")
					.withStyle(ChatFormatting.GRAY));
		}

		ctx.getSource().sendSuccess(() -> message, apply);
		return total;
	}

	/** Everyone the world has a statistics file for, whether or not they ever came back. */
	private static List<UUID> trackedByVanilla(Path stats) {
		if (!Files.isDirectory(stats)) {
			return List.of();
		}

		try (Stream<Path> files = Files.list(stats)) {
			return files.map(file -> file.getFileName().toString())
					.filter(file -> file.endsWith(".json"))
					.map(file -> uuidOrNull(file.substring(0, file.length() - ".json".length())))
					.filter(Objects::nonNull)
					.toList();
		} catch (IOException e) {
			DeathCounter.LOGGER.error("Could not list {}", stats, e);
			return List.of();
		}
	}

	private static UUID uuidOrNull(String text) {
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			// Something else living in the stats folder. Not ours to worry about.
			return null;
		}
	}

	/**
	 * What vanilla counted. Online players are read from their live counter, because their file is
	 * only written when the world saves and would be short by everything since.
	 */
	private static int vanillaDeaths(MinecraftServer server, Path stats, UUID uuid) {
		ServerPlayer online = server.getPlayerList().getPlayer(uuid);

		// The constructor parses the file and runs it through the data fixer, so old worlds work too.
		ServerStatsCounter counter = online != null
				? server.getPlayerList().getPlayerStats(online)
				: new ServerStatsCounter(server, stats.resolve(uuid + ".json"));

		return counter.getValue(Stats.CUSTOM.get(Stats.DEATHS));
	}

	/** Our own record first, then the server's name cache, and a bare UUID as the last resort. */
	private static String name(MinecraftServer server, DeathData data, UUID uuid) {
		String known = data.name(uuid);

		if (known != null) {
			return known;
		}

		return server.services().nameToIdCache().get(uuid).map(NameAndId::name).orElse(uuid.toString());
	}

	private static int setVisibility(CommandContext<CommandSourceStack> ctx, Config.CoordVisibility value) {
		Config.setCoordVisibility(value);
		return reportVisibility(ctx, "coordVisibility is now ", true);
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		Config.load();
		return reportVisibility(ctx, "Config reloaded, coordVisibility = ", true);
	}

	/**
	 * @param toOps whether other operators should see this too — yes when the setting changed,
	 *              no when someone merely asked what it is
	 */
	private static int reportVisibility(CommandContext<CommandSourceStack> ctx, String prefix, boolean toOps) {
		ctx.getSource().sendSuccess(() -> Component
				.literal(prefix + Config.coordVisibility())
				.withStyle(ChatFormatting.GRAY), toOps);
		return 1;
	}

	// --- rendering ----------------------------------------------------------------------------

	/**
	 * One death, as {@code #7 2026-08-13 14:02 <vanilla death message> [coords]}. The number counts
	 * from the oldest death, which is also what {@code /deathsadmin tp} takes.
	 */
	private static MutableComponent entry(CommandSourceStack source, Target target, DeathData.Death death,
			int number, boolean admin) {
		// Every part carries its own colour: a style on the parent would bleed into the vanilla
		// death message, which brings its own formatting.
		MutableComponent line = Component.literal("#" + number + " ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(time(death) + "  ").withStyle(ChatFormatting.GRAY))
				.append(death.message().copy().withStyle(ChatFormatting.WHITE));

		// An imported death has no position, only a placeholder one — never offer it as a location.
		if (!death.isUnknown() && Config.maySeeCoords(source, target.uuid(), admin)) {
			MutableComponent coords = Component
					.literal("  " + DeathCounter.posText(death))
					.withStyle(ChatFormatting.AQUA);

			// The link targets a command that only exists under /deathsadmin, so the permission
			// check already happened in the tree — no second one needed here.
			if (admin) {
				coords.withStyle(style -> style
						.withClickEvent(new ClickEvent.RunCommand(
								command(true, "tp " + target.name() + " " + number)))
						.withHoverEvent(new HoverEvent.ShowText(
								Component.literal("Teleport here — " + death.causeId()))));
			}

			line.append(coords);
		}

		return line;
	}

	/**
	 * The header sits inside an empty, unstyled root rather than being the root itself. A style on
	 * the root bleeds into everything appended after it, and bold in particular is not reset by
	 * giving a child a plain colour.
	 */
	private static MutableComponent header(String text) {
		return Component.empty().append(Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
	}

	/** Only worth showing when there is more than one page — otherwise it is pure noise. */
	private static Component footer(int page, int pages, String nextCommand) {
		MutableComponent footer = Component.literal("\nPage " + page + "/" + pages);

		if (page < pages) {
			footer.append(Component.literal(" — next: " + nextCommand));
		}

		return footer.withStyle(ChatFormatting.GRAY);
	}

	/** The placeholder keeps the width of a real timestamp, so the messages after it stay aligned. */
	private static String time(DeathData.Death death) {
		return death.isUnknown() ? "??-?? ??:??" : TIME.format(Instant.ofEpochMilli(death.timestamp()));
	}

	private static String count(int deaths) {
		return deaths + (deaths == 1 ? " death" : " deaths");
	}

	/** {@code outOfWorld} reads as {@code out of world}; the raw ids are developer-facing. */
	private static String cause(String causeId) {
		return causeId.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
	}

	private static String command(boolean admin, String rest) {
		return (admin ? "/" + ADMIN + " " : "/deaths ") + rest;
	}

	private static int pageCount(int entries) {
		return Math.max(1, (entries + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	// --- lookups ------------------------------------------------------------------------------

	private static List<DeathData.Death> deaths(CommandContext<CommandSourceStack> ctx, Target target) {
		return DeathData.get(ctx.getSource().getServer()).deaths(target.uuid());
	}

	private static int reportNoDeaths(CommandContext<CommandSourceStack> ctx, Target target) {
		ctx.getSource().sendSuccess(
				() -> Component.literal(target.name() + " has never died.").withStyle(ChatFormatting.GRAY), false);
		return 0;
	}

	private static Target self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		return new Target(player.getUUID(), player.getScoreboardName());
	}

	private static Target target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, "player");

		if (profiles.size() != 1) {
			throw ERROR_ONE_PLAYER.create();
		}

		NameAndId profile = profiles.iterator().next();
		return new Target(profile.id(), profile.name());
	}

	private static CompletableFuture<Suggestions> suggestTracked(CommandContext<CommandSourceStack> ctx,
			SuggestionsBuilder builder) {
		DeathData data = DeathData.get(ctx.getSource().getServer());

		return SharedSuggestionProvider.suggest(
				data.tracked().stream().map(data::name).filter(Objects::nonNull).toList(), builder);
	}
}