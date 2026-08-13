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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
						.then(playerArgument().executes(ctx -> reset(ctx, target(ctx)))))
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

	private static int reset(CommandContext<CommandSourceStack> ctx, Target target) {
		MinecraftServer server = ctx.getSource().getServer();
		DeathData data = DeathData.get(server);
		int had = data.count(target.uuid());

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
				.append(Component.literal(TIME.format(Instant.ofEpochMilli(death.timestamp())) + "  ")
						.withStyle(ChatFormatting.GRAY))
				.append(death.message().copy().withStyle(ChatFormatting.WHITE));

		if (Config.maySeeCoords(source, target.uuid(), admin)) {
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