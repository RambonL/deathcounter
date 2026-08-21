package com.rambonl.deathcounter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every death ever recorded, stored as {@code world/data/deathcounter/deaths.dat}.
 *
 * <p>Uses the server-wide storage, not {@code ServerLevel#getDataStorage()} — the latter is per
 * dimension and would give us one counter for the overworld, one for the nether and one for the end.
 */
public class DeathData extends SavedData {

	/** A single death. Never changes once recorded. */
	public record Death(long timestamp, ResourceKey<Level> dimension, BlockPos pos, String causeId, Component message) {
		public static final Codec<Death> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.LONG.fieldOf("timestamp").forGetter(Death::timestamp),
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Death::dimension),
				BlockPos.CODEC.fieldOf("pos").forGetter(Death::pos),
				Codec.STRING.fieldOf("cause").forGetter(Death::causeId),
				ComponentSerialization.CODEC.fieldOf("message").forGetter(Death::message)
		).apply(instance, Death::new));

		/**
		 * A death taken over from vanilla's own counter, which knows only that it happened. Dimension
		 * and position are filler so the codec has something to write; the zero timestamp is what
		 * marks the death as having no known time, place or cause.
		 */
		public static Death unknown(String name) {
			return new Death(0L, Level.OVERWORLD, BlockPos.ZERO, "unknown",
					Component.translatable("death.attack.generic", name));
		}

		public boolean isUnknown() {
			return timestamp == 0L;
		}
	}

	/** One player's deaths. Mutable, because the list grows with every death. */
	public static final class Entry {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("name").forGetter(entry -> entry.name),
				Death.CODEC.listOf().fieldOf("deaths").forGetter(entry -> entry.deaths)
		).apply(instance, Entry::new));

		private String name;
		private final List<Death> deaths;

		private Entry(String name, List<Death> deaths) {
			this.name = name;
			// Codecs decode into immutable lists, so copy before anything tries to append.
			this.deaths = new ArrayList<>(deaths);
		}
	}

	public static final Codec<DeathData> CODEC = Codec
			.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC)
			.xmap(DeathData::new, data -> data.players)
			.fieldOf("players")
			.codec();

	/** The null is the data fixer type: vanilla's migration mechanism, which our data has none of. */
	public static final SavedDataType<DeathData> TYPE = new SavedDataType<>(
			DeathCounter.id("deaths"), () -> new DeathData(Map.of()), CODEC, null);

	// ponytail: uncapped history, all of it in memory, and every Death keeps the vanilla message as
	// a full component tree including the click and hover events on the killer's name (~100 bytes
	// each on disk, considerably more live). Kept because a component renders in each viewer's own
	// language, where a flattened string would freeze it to the server locale. If memory ever
	// becomes the problem, store message as plain text before reaching for a database.
	private final Map<UUID, Entry> players;

	DeathData(Map<UUID, Entry> players) {
		this.players = new HashMap<>(players);
	}

	public static DeathData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public void add(ServerPlayer player, Death death) {
		add(player.getUUID(), player.getScoreboardName(), death);
	}

	/** Records a death and refreshes the stored name, so renames do not orphan the history. */
	public void add(UUID player, String name, Death death) {
		Entry entry = players.computeIfAbsent(player, uuid -> new Entry(name, List.of()));
		entry.name = name;
		entry.deaths.add(death);
		setDirty();
	}

	/**
	 * Puts deaths in front of everything recorded so far, and creates the entry if the player has
	 * none yet. Only for imports: those deaths happened before the mod was installed, so they belong
	 * at the old end of the history. An existing name is left alone, being the more recent one.
	 */
	public void prepend(UUID player, String name, List<Death> older) {
		if (older.isEmpty()) {
			return;
		}

		players.computeIfAbsent(player, uuid -> new Entry(name, List.of())).deaths.addAll(0, older);
		setDirty();
	}

	public int count(UUID player) {
		Entry entry = players.get(player);
		return entry == null ? 0 : entry.deaths.size();
	}

	/** Oldest first. Empty for a player who has never died. */
	public List<Death> deaths(UUID player) {
		Entry entry = players.get(player);
		return entry == null ? List.of() : Collections.unmodifiableList(entry.deaths);
	}

	/** The name this player last died under, or null if they never have. */
	public String name(UUID player) {
		Entry entry = players.get(player);
		return entry == null ? null : entry.name;
	}

	public Set<UUID> tracked() {
		return Collections.unmodifiableSet(players.keySet());
	}

	public void reset(UUID player) {
		if (players.remove(player) != null) {
			setDirty();
		}
	}
}