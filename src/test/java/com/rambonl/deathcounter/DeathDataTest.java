package com.rambonl.deathcounter;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathDataTest extends BootstrappedTest {
	private static final UUID ALPHA = UUID.randomUUID();
	private static final UUID BRAVO = UUID.randomUUID();

	private static DynamicOps<Tag> ops;

	/** The stored death message is a component, and those are decoded against the registries. */
	@BeforeAll
	static void registryOps() {
		ops = RegistryOps.create(NbtOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
	}

	private static DeathData empty() {
		return new DeathData(Map.of());
	}

	private static DeathData.Death death(long timestamp, int x) {
		return new DeathData.Death(timestamp, Level.NETHER, new BlockPos(x, 64, -x), "fall",
				Component.literal("Alpha fell from a high place"));
	}

	@Test
	void countsNothingForAnUnknownPlayer() {
		DeathData data = empty();

		assertEquals(0, data.count(ALPHA));
		assertTrue(data.deaths(ALPHA).isEmpty());
		assertNull(data.name(ALPHA));
		assertTrue(data.tracked().isEmpty());
	}

	@Test
	void keepsDeathsOldestFirst() {
		DeathData data = empty();
		data.add(ALPHA, "Alpha", death(100L, 1));
		data.add(ALPHA, "Alpha", death(200L, 2));

		assertEquals(2, data.count(ALPHA));
		assertEquals(List.of(100L, 200L), data.deaths(ALPHA).stream().map(DeathData.Death::timestamp).toList());
		assertEquals(Set.of(ALPHA), data.tracked());
	}

	@Test
	void refreshesTheNameOnEveryDeath() {
		DeathData data = empty();
		data.add(ALPHA, "Alpha", death(100L, 1));
		data.add(ALPHA, "AlphaRenamed", death(200L, 2));

		assertEquals("AlphaRenamed", data.name(ALPHA), "a rename must not orphan the history");
		assertEquals(2, data.count(ALPHA), "and must not start a second history either");
	}

	@Test
	void resetRemovesOnlyThatPlayer() {
		DeathData data = empty();
		data.add(ALPHA, "Alpha", death(100L, 1));
		data.add(BRAVO, "Bravo", death(100L, 1));

		data.reset(ALPHA);

		assertEquals(0, data.count(ALPHA));
		assertNull(data.name(ALPHA));
		assertEquals(1, data.count(BRAVO));
	}

	@Test
	void prependPutsImportedDeathsAtTheOldEnd() {
		DeathData data = empty();
		data.add(ALPHA, "Alpha", death(100L, 1));

		data.prepend(ALPHA, "Alpha", Collections.nCopies(3, DeathData.Death.unknown("Alpha")));

		List<DeathData.Death> deaths = data.deaths(ALPHA);
		assertEquals(4, deaths.size());
		assertTrue(deaths.get(0).isUnknown());
		assertTrue(deaths.get(2).isUnknown());
		assertFalse(deaths.get(3).isUnknown(), "the real death stays the newest");
	}

	@Test
	void prependCreatesTheEntryForSomeoneWeNeverSaw() {
		DeathData data = empty();
		data.prepend(BRAVO, "Bravo", List.of(DeathData.Death.unknown("Bravo")));

		assertEquals(1, data.count(BRAVO));
		assertEquals("Bravo", data.name(BRAVO));
	}

	@Test
	void prependOfNothingChangesNothing() {
		DeathData data = empty();
		data.prepend(BRAVO, "Bravo", List.of());

		assertEquals(0, data.count(BRAVO));
		assertTrue(data.tracked().isEmpty(), "an empty import must not conjure up an entry");
	}

	@Test
	void unknownDeathsAreMarkedByTheZeroTimestamp() {
		assertTrue(DeathData.Death.unknown("Alpha").isUnknown());
		assertFalse(death(1L, 0).isUnknown(), "the epoch is the only unknown marker");
	}

	@Test
	void survivesTheCodecRoundTrip() {
		DeathData data = empty();
		data.add(ALPHA, "Alpha", death(1_700_000_000_000L, 12));
		data.add(ALPHA, "Alpha", death(1_700_000_001_000L, -34));
		data.add(BRAVO, "Bravo", DeathData.Death.unknown("Bravo"));

		Tag encoded = DeathData.CODEC.encodeStart(ops, data).getOrThrow();
		DeathData decoded = DeathData.CODEC.parse(new Dynamic<>(ops, encoded)).getOrThrow();

		assertEquals(data.tracked(), decoded.tracked());
		assertEquals("Alpha", decoded.name(ALPHA));
		assertEquals("Bravo", decoded.name(BRAVO));
		assertEquals(2, decoded.count(ALPHA));
		assertTrue(decoded.deaths(BRAVO).getFirst().isUnknown());

		DeathData.Death first = decoded.deaths(ALPHA).getFirst();
		DeathData.Death original = data.deaths(ALPHA).getFirst();
		assertEquals(original.timestamp(), first.timestamp());
		assertEquals(original.dimension(), first.dimension());
		assertEquals(original.pos(), first.pos());
		assertEquals(original.causeId(), first.causeId());
		assertEquals(original.message(), first.message());
	}

	@Test
	void decodedHistoryStaysAppendable() {
		DeathData data = empty();
		data.add(ALPHA, "Alpha", death(100L, 1));

		Tag encoded = DeathData.CODEC.encodeStart(ops, data).getOrThrow();
		DeathData decoded = DeathData.CODEC.parse(new Dynamic<>(ops, encoded)).getOrThrow();

		// Codecs decode into immutable lists; Entry copies them for exactly this reason.
		decoded.add(ALPHA, "Alpha", death(200L, 2));

		assertEquals(2, decoded.count(ALPHA));
	}
}