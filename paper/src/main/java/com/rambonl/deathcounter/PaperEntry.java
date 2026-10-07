package com.rambonl.deathcounter;

import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.craftbukkit.damage.CraftDamageSource;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Paper half of the mod: config directory and event wiring, nothing else. Everything it calls
 * lives in {@link DeathCounter} and is shared with Fabric and NeoForge. See MULTILOADER.md.
 *
 * <p>The shared code talks to the vanilla server, so this class unwraps Bukkit's objects with
 * {@code getHandle()} and hands the vanilla ones on. That is Paper's internals, not its API, and
 * comes with no stability promise between Paper builds.
 */
public class PaperEntry extends JavaPlugin implements Listener {
	@Override
	@SuppressWarnings("unchecked")
	public void onEnable() {
		// plugins/DeathCounter, where a Paper admin looks for it, rather than config/.
		DeathCounter.init(getDataPath());

		// Paper's dispatcher is the vanilla one under its API type: the vanilla CommandSourceStack
		// implements Paper's interface of the same name. Fires again on every /reload.
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> DeathCommands.register(
				(CommandDispatcher<CommandSourceStack>) (CommandDispatcher<?>) event.registrar().getDispatcher()));

		getServer().getPluginManager().registerEvents(this, this);
	}

	@EventHandler
	public void onServerLoad(ServerLoadEvent event) {
		DeathCounter.setUpObjective(MinecraftServer.getServer());
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
		DeathCounter.syncScore(player.level().getServer(), player);
	}

	// PlayerDeathEvent fires before the death and can be cancelled. MONITOR puts us last and
	// ignoreCancelled keeps cancelled deaths away, so we only run when the death really goes
	// through — the same guarantee Fabric's AFTER_DEATH gives.
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDeath(PlayerDeathEvent event) {
		DeathCounter.onDeath(((CraftPlayer) event.getEntity()).getHandle(),
				((CraftDamageSource) event.getDamageSource()).getHandle());
	}
}
