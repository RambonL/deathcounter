package com.rambonl.deathcounter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.commands.CommandSourceStack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Mod settings, stored as {@code config/deathcounter.json}.
 */
public final class Config {
	/** Who may see death coordinates. Ops always see them through {@code /deathsadmin}. */
	public enum CoordVisibility {
		/** Nobody, not even the player who died. */
		HIDDEN,
		/** Only the player who died, and only for their own deaths. */
		SELF,
		/** Everyone, including the death broadcast in chat. */
		PUBLIC
	}

	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(DeathCounter.MOD_ID + ".json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Config current = new Config();

	// Gson maps this field to the JSON key of the same name.
	private CoordVisibility coordVisibility = CoordVisibility.SELF;

	private Config() {
	}

	public static CoordVisibility coordVisibility() {
		return current.coordVisibility;
	}

	public static void setCoordVisibility(CoordVisibility visibility) {
		current.coordVisibility = visibility;
		save();
	}

	/**
	 * Whether the public death broadcast may carry coordinates. Only {@code PUBLIC} qualifies —
	 * {@code SELF} means the player can look their own up, not that everyone reads them in chat.
	 */
	public static boolean showCoordsInBroadcast() {
		return coordVisibility() == CoordVisibility.PUBLIC;
	}

	/**
	 * The single gate for coordinates in command output. Every path that prints a position asks
	 * this first; anything printing around it leaks positions past the configured mode.
	 *
	 * @param admin whether this ran under {@code /deathsadmin}, which Brigadier already gated
	 */
	public static boolean maySeeCoords(CommandSourceStack source, UUID target, boolean admin) {
		if (admin) {
			return true;
		}

		return switch (coordVisibility()) {
			case HIDDEN -> false;
			case SELF -> source.getPlayer() != null && source.getPlayer().getUUID().equals(target);
			case PUBLIC -> true;
		};
	}

	public static void load() {
		Config loaded;

		try (Reader reader = Files.newBufferedReader(FILE)) {
			loaded = GSON.fromJson(reader, Config.class);
		} catch (NoSuchFileException e) {
			save(); // First start: write the defaults so there is a file to edit.
			return;
		} catch (IOException | JsonParseException e) {
			DeathCounter.LOGGER.error("Could not read {}, keeping the current settings", FILE, e);
			return;
		}

		// Gson leaves the field null for an empty file or an unknown enum constant.
		if (loaded == null || loaded.coordVisibility == null) {
			DeathCounter.LOGGER.warn("{} has no valid coordVisibility, keeping {}", FILE, coordVisibility());
			return;
		}

		current = loaded;
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());

			try (Writer writer = Files.newBufferedWriter(FILE)) {
				GSON.toJson(current, writer);
			}
		} catch (IOException e) {
			DeathCounter.LOGGER.error("Could not write {}", FILE, e);
		}
	}
}