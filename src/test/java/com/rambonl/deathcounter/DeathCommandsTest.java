package com.rambonl.deathcounter;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathCommandsTest extends BootstrappedTest {
	private static DeathData.Death death(long timestamp) {
		return new DeathData.Death(timestamp, Level.OVERWORLD, BlockPos.ZERO, "fall", Component.literal("fell"));
	}

	// --- pagination -----------------------------------------------------------------------------

	@Test
	void pageCountNeverDropsBelowOne() {
		assertEquals(1, DeathCommands.pageCount(0), "an empty history is still page 1 of 1");
		assertEquals(1, DeathCommands.pageCount(1));
		assertEquals(1, DeathCommands.pageCount(10));
		assertEquals(2, DeathCommands.pageCount(11));
		assertEquals(2, DeathCommands.pageCount(20));
		assertEquals(3, DeathCommands.pageCount(21));
	}

	@Test
	void firstPageIsTheNewestTen() {
		DeathCommands.Window window = DeathCommands.window(25, 1);

		assertEquals(15, window.start());
		assertEquals(25, window.end());
		assertEquals(1, window.page());
		assertEquals(3, window.pages());
	}

	@Test
	void laterPagesWalkBackwards() {
		assertEquals(5, DeathCommands.window(25, 2).start());
		assertEquals(15, DeathCommands.window(25, 2).end());

		assertEquals(0, DeathCommands.window(25, 3).start());
		assertEquals(5, DeathCommands.window(25, 3).end(), "the last page is the short one");
	}

	@Test
	void everyDeathAppearsOnExactlyOnePage() {
		int size = 37;
		int seen = 0;

		for (int page = 1; page <= DeathCommands.pageCount(size); page++) {
			DeathCommands.Window window = DeathCommands.window(size, page);
			seen += window.end() - window.start();
		}

		assertEquals(size, seen);
	}

	@Test
	void pagesPastTheEndClampToTheLastOne() {
		DeathCommands.Window last = DeathCommands.window(25, 3);
		DeathCommands.Window past = DeathCommands.window(25, 99);

		assertEquals(last, past);
	}

	@Test
	void aSinglePageFitsExactly() {
		DeathCommands.Window window = DeathCommands.window(4, 1);

		assertEquals(0, window.start());
		assertEquals(4, window.end());
		assertEquals(1, window.pages(), "no footer for a single page");
	}

	// --- rendering ------------------------------------------------------------------------------

	@Test
	void importedDeathsKeepTheTimestampColumnWidth() {
		String unknown = DeathCommands.time(DeathData.Death.unknown("Alpha"));
		String known = DeathCommands.time(death(1_700_000_000_000L));

		assertEquals("??-?? ??:??", unknown);
		assertEquals(known.length(), unknown.length(), "the placeholder keeps the messages aligned");
	}

	@Test
	void oneDeathIsSingular() {
		assertEquals("1 death", DeathCommands.count(1));
		assertEquals("0 deaths", DeathCommands.count(0));
		assertEquals("2 deaths", DeathCommands.count(2));
	}

	@Test
	void causeIdsBecomeReadable() {
		assertEquals("out of world", DeathCommands.cause("outOfWorld"));
		assertEquals("fall", DeathCommands.cause("fall"));
		assertEquals("lightning bolt", DeathCommands.cause("lightningBolt"));
	}

	@Test
	void commandsPointAtTheRightRoot() {
		assertEquals("/deaths history Alpha 2", DeathCommands.command(false, "history Alpha 2"));
		assertEquals("/deathsadmin tp Alpha 7", DeathCommands.command(true, "tp Alpha 7"));
	}

	@Test
	void theFooterOffersTheNextPageOnlyWhenThereIsOne() {
		assertTrue(DeathCommands.footer(1, 3, "/deaths top 2").getString().contains("next: /deaths top 2"));
		assertEquals("\nPage 3/3", DeathCommands.footer(3, 3, "/deaths top 4").getString());
	}
}