package com.rambonl.deathcounter;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;

/**
 * Brings Minecraft up far enough for the registries to work.
 *
 * <p>Anything reaching {@code Level} or the component codecs initializes {@code BuiltInRegistries},
 * which throws when the bootstrap has not run — and a class that fails to initialize stays failed.
 * Gradle runs every test class in one JVM, so a single class touching those without asking first
 * takes the whole run down with it. Extending this is therefore cheaper than deciding per class;
 * the bootstrap itself only ever does the work once.
 */
abstract class BootstrappedTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}
}