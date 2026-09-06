/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.neoforge.globals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.neoforge.tick.EntityTickRunnerBridge;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.globals.BossEventSystem;
import net.multiforge.runtime.globals.CommandDispatchSystem;
import net.multiforge.runtime.globals.DragonFightSystem;
import net.multiforge.runtime.globals.GlobalSystems;
import net.multiforge.runtime.globals.RaidStateSnapshot;
import net.multiforge.runtime.globals.RaidsSystem;
import net.multiforge.runtime.globals.ScoreboardSystem;
import net.multiforge.runtime.globals.TimeSystem;
import net.multiforge.runtime.globals.WeatherSystem;
import net.multiforge.runtime.globals.WorldBorderSystem;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.ThreadedRegionizer;
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

        // MultiForge M5 (Track B, B2.6): register the Raids global
        // subsystem — raid wave-scheduling/spawn-decision tick, migrated
        // off the Vanilla-inline per-level tick (docs/design/global-region.md
        // §4.2/§6.3). Registered after the B2low systems above per §4.3's
        // ordering convention (B2low before B2high). Raider spawn always
        // routes via EntityMigrationCoordinator.spawnInDestRegion — see
        // RaidsSystem's javadoc and §8.2 integration test 6.
        RaidsSystem raids = new RaidsSystem(effects, host.entityMigrationCoordinator()::spawnInDestRegion);
        systems.register(raids);
        GlobalSystemsBridge.bindRaids(raids);
        installRaidsLevelLifecycleListeners(raids);

        // MultiForge M5 (Track B, B2.7): register the DragonFight global
        // subsystem — EndDragonFight's phase state machine, migrated off
        // the Vanilla-inline per-level tick (docs/design/global-region.md
        // §5/§6.3). Registered last per §4.3's B2low-before-B2high
        // ordering convention. Fresh-dragon-entity spawns (respawn
        // completion) route via the same EntitySpawner surface RaidsSystem
        // uses, backed by EntityMigrationCoordinator#spawnInDestRegion.
        DragonFightSystem dragonFight = new DragonFightSystem(effects, host.entityMigrationCoordinator()::spawnInDestRegion);
        systems.register(dragonFight);
        GlobalSystemsBridge.bindDragonFight(dragonFight);
        installDragonFightLevelLifecycleListeners(dragonFight, host);

        // MultiForge M5 (Track B, B2.8): register the CommandDispatch global
        // subsystem — the last of the eight B2.x migrations. Registered
        // server-wide (not per-level, unlike every B2low/B2.6/B2.7 target)
        // since Commands.performPrefixedCommand/ServerFunctionManager.execute
        // are server-wide entry points with no per-level registration to
        // hook, per docs/design/global-region.md §6.3's command-dispatch row
        // and the plan's Track B2.8 task. "multiforge:global" matches the
        // synthetic global world's WorldRef constructed by
        // MultiThreadedSchedulerHost's own constructor (§1.1) — not
        // re-derived from a field accessor since none is exposed; the
        // literal is the same stable contract host.globalRegion() ticks
        // against.
        CommandDispatchSystem commandDispatch = new CommandDispatchSystem(
                effects,
                host.taskQueue()::queueChunkTask,
                net.multiforge.api.world.WorldRef.of("multiforge:global"),
                host.entityMigrationCoordinator());
        systems.register(commandDispatch);
        GlobalSystemsBridge.bindCommandDispatch(commandDispatch);

        // MultiForge M13 (Track B3, B3.2): register the Vanilla-backed
        // ScheduledTickRunner so the BLOCK_FLUID_TICKS phase slot
        // (docs/design/m13-b3-region-tick.md §5.1) actually drains each
        // region's owned chunks' scheduled block/fluid ticks instead of
        // defaulting to the runtime's no-op. installOnEventBus is
        // idempotent (guards its own INSTALLED flag) so re-running this
        // whole install() on a reused-JVM GameTestServer restart is safe.
        net.multiforge.neoforge.tick.ScheduledTickRunnerBridge.installOnEventBus();
        host.setBlockFluidRunner(new net.multiforge.neoforge.tick.ScheduledTickRunnerBridge());

        // MultiForge M13 (B3.3): bind the per-region ENTITY_AI runner —
        // docs/design/m13-b3-region-tick.md §5.2. Unlike the eight B2
        // subsystems above, this isn't a GlobalSystem (it runs on every
        // real region's own worker, not the synthetic global region), so
        // it is bound directly onto the host rather than through
        // `systems.register`. RegionizedTickCoordinator.regionsHandleEntityTicks
        // starts reporting true for a world the instant its regionizer
        // materialises, since `host.hasEntityTickRunner()` is now true
        // host-wide from this point on.
        host.setEntityTickRunner(new EntityTickRunnerBridge(host, server));

        // MultiForge M13 (Track B3, B3.4): install the per-region
        // BLOCK_ENTITIES bridge (docs/design/m13-b3-region-tick.md §5.3)
        // — every server ServerLevel that loads from here on has its
        // Vanilla-added block-entity tickers routed into the owning
        // region's HolderManagerRegionData.blockEntityTickers slice, and
        // the Level.tickBlockEntities() inline iteration skipped in
        // favour of MultiThreadedSchedulerHost's own per-region phase
        // body. Installed last, after every B2.x global subsystem, per
        // this method's own registration-order convention — B3.4 has no
        // tick-order dependency on any of them, so "last" simply keeps
        // this diff at the bottom of an already-long method.
        installBlockEntityTickerBridgeListeners();

        // MultiForge M12 (Task 4.2, M12.2, docs/design/m12-event-routing.md
        // §2.1/§12): attach the scheduler-backed dispatch executor onto
        // NeoForge.EVENT_BUS's LazyDispatchingEventBus (installed at
        // class-load time by the 09-events/NeoForge.java.patch hunk).
        // Until this runs, every listener — regardless of its declared
        // @DispatchDomain — dispatches inline (pre-M12 Vanilla-equivalent
        // behavior; see LazyDispatchingEventBus's javadoc). Idempotent
        // (EventBusBridge.attach → LazyDispatchingEventBus.attachExecutor
        // is a CAS), so a GameTestServer restart on a reused JVM re-running
        // install() is safe. Registered last, after every other
        // B2.x/B3.x subsystem, since it has no tick-order dependency on
        // any of them.
        boolean eventBusAttached = net.multiforge.neoforge.event.EventBusBridge.attach(NeoForge.EVENT_BUS, host);
        if (!eventBusAttached) {
            // Auto-reroute+warn (CLAUDE.md rule 5): NeoForge.EVENT_BUS is not a
            // LazyDispatchingEventBus — the 09-events patch was not applied, or
            // something else replaced the bus. This must never prevent the
            // server from finishing boot: every listener still dispatches via
            // the real bus's own (un-routed) semantics, exactly as it did
            // before M12.
            ViolationLogger.warn(
                    "global.system.registration",
                    "failed to attach SchedulerBackedDispatchExecutor onto NeoForge.EVENT_BUS "
                            + "(not a LazyDispatchingEventBus); events will dispatch without M12 domain routing");
        }
    }

    private static void installBlockEntityTickerBridgeListeners() {
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            net.multiforge.neoforge.tick.BlockEntityTickerBridge.installOnLevel(level);
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            net.multiforge.neoforge.tick.BlockEntityTickerBridge.uninstallLevel(level);
        });
    }

    private static void installDragonFightLevelLifecycleListeners(
            DragonFightSystem dragonFight, MultiThreadedSchedulerHost host) {
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            if (level.dimension() != Level.END) return;
            registerDragonFightLevel(dragonFight, level, host);
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            if (level.dimension() != Level.END) return;
            unregisterDragonFightLevel(dragonFight, level);
        });
    }

    /**
     * Binds one End {@link ServerLevel}'s {@code EndDragonFight} instance to {@link
     * DragonFightSystem}. {@code tickBody()}/{@code shouldPin()} invoke the extracted-verbatim
     * Vanilla {@code EndDragonFight.mfTickBody()}/{@code mfHasNearbyPlayers()} the {@code
     * 08-globals/EndDragonFight.java.patch} hunk exposed — see that patch's javadoc for the
     * extraction rationale. {@code arenaChunks()} is computed once from the fight's origin (fixed
     * for the life of the {@code EndDragonFight} instance) rather than every call. Respawn-triggered
     * dragon spawning is left unwired ({@code pollPendingSpawn()} keeps {@link
     * DragonFightSystem.DragonFightTarget}'s default {@code null}) — the natural Vanilla respawn
     * path already runs safely: {@code mfTickBody()} itself (including any {@code
     * createNewDragon()}/{@code addFreshEntity} it performs) is dispatched by {@link
     * DragonFightSystem} onto the arena's owning region via {@code crossRegionEffect} before this
     * method's {@code tickBody()} ever runs, so a respawn completing mid-body already touches only
     * that region's own entities from that region's own worker thread.
     */
    private static void registerDragonFightLevel(
            DragonFightSystem dragonFight, ServerLevel level, MultiThreadedSchedulerHost host) {
        try {
            EndDragonFight fight = level.getDragonFight();
            if (fight == null) return;
            WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
            dragonFight.registerWorld(world, new DragonFightSystem.DragonFightTarget() {
                private volatile List<ChunkPos> arenaChunks;

                @Override
                public RegionId currentRegion() {
                    ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
                    if (regionizer == null) return null;
                    BlockPos origin = mfOrigin();
                    ChunkPos originChunk = origin.toChunkPos();
                    Region region = regionizer.regionAtChunk(originChunk.x(), originChunk.z());
                    return region == null ? null : region.id();
                }

                @Override
                public ChunkHolderManager chunkHolderManager() {
                    return host.chunkManagerForOrNull(world);
                }

                @Override
                public List<ChunkPos> arenaChunks() {
                    List<ChunkPos> cached = arenaChunks;
                    if (cached != null) return cached;
                    List<ChunkPos> computed = computeArenaChunks(mfOrigin());
                    arenaChunks = computed;
                    return computed;
                }

                @Override
                public boolean shouldPin() {
                    return fight.mfHasNearbyPlayers();
                }

                @Override
                public void tickBody() {
                    fight.mfTickBody();
                }

                private BlockPos mfOrigin() {
                    net.minecraft.core.BlockPos o = fight.mfOrigin();
                    return new BlockPos(o.getX(), o.getY(), o.getZ());
                }
            });
        } catch (Throwable t) {
            // Auto-reroute+warn (CLAUDE.md rule 5): a registration failure for one level must
            // never prevent the server from finishing its boot/level-load sequence. Worst case,
            // this End level's dragon fight stays on the Vanilla-inline fallback path
            // (dragonFightReady() reports true server-wide once any level registers, so a
            // not-yet-registered level's tick() would otherwise silently no-op — this catch
            // prevents that by simply not letting the failure escape level-load) until a
            // subsequent load succeeds.
            ViolationLogger.warn(
                    "global.system.registration",
                    "failed to register DragonFightSystem target for level "
                            + level.dimension().location() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * End arena chunk set: the vanilla {@code ARENA_SIZE_CHUNKS} (8) radius around the fight
     * origin, matching {@code EndDragonFight.isArenaLoaded}'s own scan range.
     */
    private static List<ChunkPos> computeArenaChunks(BlockPos origin) {
        ChunkPos originChunk = origin.toChunkPos();
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                chunks.add(new ChunkPos(originChunk.x() + dx, originChunk.z() + dz));
            }
        }
        return chunks;
    }

    private static void unregisterDragonFightLevel(DragonFightSystem dragonFight, ServerLevel level) {
        try {
            dragonFight.unregisterWorld(RegionizedTickCoordinator.asWorldRef(level));
        } catch (Throwable t) {
            ViolationLogger.warn(
                    "global.system.registration",
                    "failed to unregister DragonFightSystem target for level "
                            + level.dimension().location() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void installRaidsLevelLifecycleListeners(RaidsSystem raids) {
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            registerRaidsLevel(raids, level);
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            unregisterRaidsLevel(raids, level);
        });
    }

    /**
     * Binds one {@link ServerLevel}'s {@code Raids} instance to {@link RaidsSystem}. The target
     * invokes the extracted-verbatim Vanilla {@code Raids.mfTickBody()} the {@code
     * 08-globals/Raids.java.patch} hunk exposed — see that patch's javadoc for the extraction
     * rationale. Per-raid {@link RaidStateSnapshot} reporting is left empty for now: {@code
     * Raids}/{@code Raid} expose no public per-raid state accessor beyond {@link
     * net.minecraft.world.entity.raid.Raids#get(int)}, so wiring real snapshots is deferred to a
     * follow-up patch that adds one; {@link RaidsSystem} already tolerates an empty/{@code null}
     * snapshot list as "nothing to record this tick," not an error.
     */
    private static void registerRaidsLevel(RaidsSystem raids, ServerLevel level) {
        try {
            WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
            raids.registerWorld(world, () -> {
                level.getRaids().mfTickBody();
                return java.util.Collections.<RaidStateSnapshot>emptyList();
            });
        } catch (Throwable t) {
            // Auto-reroute+warn (CLAUDE.md rule 5): a registration failure for one level must
            // never prevent the server from finishing its boot/level-load sequence. Worst case,
            // this level's raids stay on the Vanilla-inline fallback path (raidsReady() still
            // reports true server-wide once any level registers, so a not-yet-registered level's
            // Raids.tick() call would otherwise silently no-op — this catch is what prevents that
            // by simply not letting the failure escape level-load at all) until a subsequent load
            // succeeds.
            ViolationLogger.warn(
                    "global.system.registration",
                    "failed to register RaidsSystem target for level "
                            + level.dimension().location() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void unregisterRaidsLevel(RaidsSystem raids, ServerLevel level) {
        try {
            raids.unregisterWorld(RegionizedTickCoordinator.asWorldRef(level));
        } catch (Throwable t) {
            ViolationLogger.warn(
                    "global.system.registration",
                    "failed to unregister RaidsSystem target for level "
                            + level.dimension().location() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
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
