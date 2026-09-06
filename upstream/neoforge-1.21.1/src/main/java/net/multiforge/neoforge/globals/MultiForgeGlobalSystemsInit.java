/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge.globals;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.globals.BossEventSystem;
import net.multiforge.runtime.globals.GlobalSystems;
import net.multiforge.runtime.globals.ScoreboardSystem;
import net.multiforge.runtime.globals.TimeSystem;
import net.multiforge.runtime.globals.WeatherSystem;
import net.multiforge.runtime.globals.WorldBorderSystem;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * B2low (M5, Track B): registers the five low-risk global subsystems
 * (weather, time, world border, scoreboard, boss events) with a freshly
 * installed {@link MultiThreadedSchedulerHost}'s {@link GlobalSystems}
 * registry, and wires the {@code LevelEvent.Load}/{@code Unload}
 * listeners that keep {@link WeatherSystem}/{@link TimeSystem}/{@link
 * WorldBorderSystem}'s per-world targets (and {@link
 * GlobalSystemsBridge}'s border-handled side table) in sync with
 * Vanilla's own level lifecycle.
 *
 * <p>Called exactly once per fresh runtime install from {@code
 * ServerLifecycleHooks.handleServerAboutToStart} — see that method's
 * {@code freshInstall} guard, which is the mechanism that satisfies
 * docs/design/global-region.md §4.3's idempotency requirement (a
 * {@code GameTestServer} reusing one JVM across successive server
 * instances must not double-register every subsystem on the same,
 * reused {@link MultiThreadedSchedulerHost}).
 */
public final class MultiForgeGlobalSystemsInit {
    private MultiForgeGlobalSystemsInit() {}

    /**
     * Registers all five B2low {@link net.multiforge.runtime.globals.GlobalSystem}
     * instances on {@code host.globalSystems()}, binds {@link
     * GlobalSystemsBridge}, and installs the level-lifecycle listeners
     * on {@link NeoForge#EVENT_BUS} that populate/depopulate their
     * per-world targets.
     */
    public static void install(MultiThreadedSchedulerHost host, MinecraftServer server) {
        GlobalSystems systems = host.globalSystems();
        net.multiforge.runtime.globals.CrossRegionEffects effects = systems.effects();

        // Registration order is tick order (docs/design/global-region.md
        // §4.3) — B2low before the not-yet-landed B2high systems
        // (raids, dragon fight, command dispatch), which is naturally
        // satisfied here since this method only registers B2low.
        WeatherSystem weather = new WeatherSystem(effects);
        TimeSystem time = new TimeSystem(effects);
        WorldBorderSystem worldBorder = new WorldBorderSystem(effects);
        ScoreboardSystem scoreboard = new ScoreboardSystem(effects);
        BossEventSystem bossEvents = new BossEventSystem(effects);

        systems.register(weather);
        systems.register(time);
        systems.register(worldBorder);
        systems.register(scoreboard);
        systems.register(bossEvents);

        GlobalSystemsBridge.bind(weather, time, worldBorder, scoreboard, bossEvents);

        installLevelLifecycleListeners(weather, time, worldBorder, server);
    }

    private static void installLevelLifecycleListeners(
            WeatherSystem weather, TimeSystem time, WorldBorderSystem worldBorder, MinecraftServer server) {
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            registerLevel(weather, time, worldBorder, level, server);
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            unregisterLevel(weather, time, worldBorder, level);
        });
    }

    /**
     * Binds one {@link ServerLevel}'s weather/time/border targets. Each
     * target's method body invokes the extracted-verbatim Vanilla
     * method the corresponding {@code 08-globals} patch exposed as a
     * public {@code mf...Body()} wrapper — see e.g. {@code
     * ServerLevel.mfAdvanceWeatherCycleBody}.
     */
    private static void registerLevel(
            WeatherSystem weather, TimeSystem time, WorldBorderSystem worldBorder, ServerLevel level, MinecraftServer server) {
        try {
            WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
            weather.registerWorld(world, level::mfAdvanceWeatherCycleBody);
            time.registerWorld(world, new TimeSystem.TimeTarget() {
                @Override
                public void tickTime() {
                    level.mfTickTimeBody();
                }

                @Override
                public void broadcastTime() {
                    server.mfSynchronizeTimeBody(level);
                }
            });
            worldBorder.registerWorld(world, level.getWorldBorder()::mfTickBody);
            GlobalSystemsBridge.markBorderHandled(level.getWorldBorder(), true);
        } catch (Throwable t) {
            // Auto-reroute+warn (CLAUDE.md rule 5): a registration failure
            // for one level must never prevent the server from finishing
            // its boot/level-load sequence. Worst case, this level's
            // weather/time/border stay on the Vanilla-inline fallback
            // path (the patches' own "not handled" branch) until a
            // subsequent load succeeds.
            ViolationLogger.warn(
                    "global.system.registration",
                    "failed to register B2low targets for level "
                            + level.dimension().location() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void unregisterLevel(
            WeatherSystem weather, TimeSystem time, WorldBorderSystem worldBorder, ServerLevel level) {
        try {
            WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
            weather.unregisterWorld(world);
            time.unregisterWorld(world);
            worldBorder.unregisterWorld(world);
            GlobalSystemsBridge.markBorderHandled(level.getWorldBorder(), false);
        } catch (Throwable t) {
            ViolationLogger.warn(
                    "global.system.registration",
                    "failed to unregister B2low targets for level "
                            + level.dimension().location() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
