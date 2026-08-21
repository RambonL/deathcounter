package com.rambonl.deathcounter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest extends BootstrappedTest {
	@TempDir
	Path dir;

	private Path configFile() {
		return dir.resolve("deathcounter.json");
	}

	/**
	 * Config state is static and a failed load deliberately keeps whatever was loaded before, so
	 * every test starts from a written file rather than from whatever the previous test left behind.
	 */
	@BeforeEach
	void baseline() throws IOException {
		write("{\"coordVisibility\": \"SELF\"}");
		Config.init(dir);
	}

	private void write(String json) throws IOException {
		Files.writeString(configFile(), json);
	}

	@Test
	void writesDefaultsWhenThereIsNoFile() throws IOException {
		Files.delete(configFile());
		Config.load();

		assertTrue(Files.exists(configFile()), "first start should leave a file to edit");
		assertTrue(Files.readString(configFile()).contains("coordVisibility"));
	}

	@Test
	void readsTheFile() throws IOException {
		write("{\"coordVisibility\": \"PUBLIC\"}");
		Config.load();

		assertEquals(Config.CoordVisibility.PUBLIC, Config.coordVisibility());
	}

	@Test
	void keepsTheCurrentSettingOnAnEmptyFile() throws IOException {
		write("");
		Config.load();

		assertEquals(Config.CoordVisibility.SELF, Config.coordVisibility());
	}

	@Test
	void keepsTheCurrentSettingOnAnUnknownMode() throws IOException {
		write("{\"coordVisibility\": \"EVERYONE_BUT_STEVE\"}");
		Config.load();

		assertEquals(Config.CoordVisibility.SELF, Config.coordVisibility());
	}

	@Test
	void keepsTheCurrentSettingOnBrokenJson() throws IOException {
		write("{ this is not json");
		Config.load();

		assertEquals(Config.CoordVisibility.SELF, Config.coordVisibility());
	}

	@Test
	void changingTheModeWritesItThrough() throws IOException {
		Config.setCoordVisibility(Config.CoordVisibility.HIDDEN);

		assertTrue(Files.readString(configFile()).contains("HIDDEN"));

		Config.setCoordVisibility(Config.CoordVisibility.PUBLIC);
		Config.load();

		assertEquals(Config.CoordVisibility.PUBLIC, Config.coordVisibility());
	}

	// --- the coordinate gate --------------------------------------------------------------------

	private static final UUID VIEWER = UUID.randomUUID();
	private static final UUID OTHER = UUID.randomUUID();

	/** What the overload passes down for the console and for command blocks. */
	private static final UUID CONSOLE = null;

	@Test
	void hiddenReleasesNothing() {
		Config.setCoordVisibility(Config.CoordVisibility.HIDDEN);

		assertFalse(Config.maySeeCoords(VIEWER, VIEWER, false), "not even your own");
		assertFalse(Config.maySeeCoords(VIEWER, OTHER, false));
		assertFalse(Config.maySeeCoords(CONSOLE, OTHER, false));
	}

	@Test
	void selfReleasesOnlyYourOwn() {
		Config.setCoordVisibility(Config.CoordVisibility.SELF);

		assertTrue(Config.maySeeCoords(VIEWER, VIEWER, false));
		assertFalse(Config.maySeeCoords(VIEWER, OTHER, false));
		assertFalse(Config.maySeeCoords(CONSOLE, OTHER, false), "the console is nobody's self");
	}

	@Test
	void publicReleasesEverything() {
		Config.setCoordVisibility(Config.CoordVisibility.PUBLIC);

		assertTrue(Config.maySeeCoords(VIEWER, OTHER, false));
		assertTrue(Config.maySeeCoords(CONSOLE, OTHER, false));
	}

	@Test
	void adminOverridesEveryMode() {
		for (Config.CoordVisibility mode : Config.CoordVisibility.values()) {
			Config.setCoordVisibility(mode);

			assertTrue(Config.maySeeCoords(VIEWER, OTHER, true), "admin under " + mode);
			assertTrue(Config.maySeeCoords(CONSOLE, OTHER, true), "console admin under " + mode);
		}
	}

	@Test
	void broadcastCoordsOnlyUnderPublic() {
		Config.setCoordVisibility(Config.CoordVisibility.HIDDEN);
		assertFalse(Config.showCoordsInBroadcast());

		Config.setCoordVisibility(Config.CoordVisibility.SELF);
		assertFalse(Config.showCoordsInBroadcast(), "SELF is a lookup, not a chat announcement");

		Config.setCoordVisibility(Config.CoordVisibility.PUBLIC);
		assertTrue(Config.showCoordsInBroadcast());
	}
}