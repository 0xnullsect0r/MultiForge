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
package net.multiforge.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.RegionTickWatchdog;
import net.multiforge.runtime.region.TickRegionScheduler;
import net.multiforge.runtime.scheduler.LevelTickDispatchProbes;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Fork-local façade patched vanilla call sites in
 * {@code MinecraftServer.tickChildren} route through, so the per-level
 * tick can be dispatched to region workers rather than run inline on
 * the server thread.
 *
 * <p>Mirrors the {@link OwnershipGuard} facade shape used by M7:
 * patched vanilla code references a stable fork-local API; the actual
 * runtime behind it is swapped out from behind without editing any
 * patch file.
 *
 * <p><b>Barrier tick model.</b> The server thread still runs Vanilla's
 * main loop (network, chunk system, commands). Per server tick:
 *
 * <ol>
 * <li>{@link #dispatchLevelTick} for the first level ticked (the
 * overworld) first drives the synthetic global region once: the tasks
 * queued with {@code ServerDomains.global()} and GLOBAL-domain event
 * listeners.</li>
 * <li>Vanilla's {@code ServerLevel.tick} then runs on the server thread.
 * The patched body skips exactly the work that regions own: random ticks
 * and spawning, scheduled block/fluid ticks, block events, entity ticking
 * and block-entity ticking (see {@link #regionsHandleChunkTicks}, {@link
 * #regionsHandleBlockFluidTicks}, {@link #regionsHandleEntityTicks}, {@link
 * #regionsHandleBlockEntities}); everything else — the chunk system,
 * weather, time, raids, the dragon fight — runs as in Vanilla.</li>
 * <li>Every region of the level then ticks once, in parallel on the worker
 * pool, and this method returns only after all of them finished
 * ({@link MultiThreadedSchedulerHost#driveRegions}). While waiting, the
 * server thread services main-thread chunk requests a region worker
 * is blocked on (see {@link net.multiforge.neoforge.chunk.MainThreadHandoff}).</li>
 * </ol>
 *
 * <p>Because region work and server-thread work never overlap, running
 * Vanilla entity and block code on region workers only has to be safe
 * against other regions, which {@link OwnershipGuard}'s per-chunk checks
 * and the thread-safe shared structures take care of.
 *
 * <p>If the runtime is not installed ({@code mode = "off"}), or {@code
 * level} has no regionizer yet, Vanilla's {@code ServerLevel.tick} runs
 * unchanged: the {@code regionsHandle*} guards report {@code false} in that
 * state, so nothing is dropped (CLAUDE.md rule 5).
 *
 * <p>An exception from a region's tick is rethrown on the server thread
 * once the barrier completes, so Vanilla's crash handling applies to it
 * exactly as to an exception from an inline level tick.
 */
public final class RegionizedTickCoordinator {
    private static final String DEADLINE_PROP = "multiforge.regiontick.dispatch-ms";
    private static final long DEFAULT_DISPATCH_DEADLINE_MS = 500L;
    private static final long DISPATCH_DEADLINE_NANOS = parseDispatchDeadlineMs(System.getProperty(DEADLINE_PROP)) * 1_000_000L;

    private RegionizedTickCoordinator() {}

    /**
     * Robust parse for the dispatch-deadline sysprop that never throws
     * at class-init time. Same failure-shape contract as
     * {@link RegionTickWatchdog#parseWarnMs} — an invalid or missing
     * value falls back to {@link #DEFAULT_DISPATCH_DEADLINE_MS} rather
     * than blowing up the fork's static init.
     */
    static long parseDispatchDeadlineMs(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_DISPATCH_DEADLINE_MS;
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed < 0 ? DEFAULT_DISPATCH_DEADLINE_MS : parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_DISPATCH_DEADLINE_MS;
        }
    }

    /**
     * The fork-local replacement for Vanilla's {@code serverlevel.tick(p)}
     * call in {@code MinecraftServer.tickChildren} — see the class javadoc
     * for the barrier model.
     *
     * <p>In {@link RegionTickWatchdog.Mode#STRICT STRICT} mode a region
     * overrunning the dispatch deadline throws {@link
     * RegionDispatchOverrunException} after the barrier completed;
     * otherwise it is a probe bump plus a rate-limited warning.
     *
     * @param level    the level being ticked
     * @param haveTime Vanilla's "time left in this tick" supplier, passed
     *                 through to {@code ServerLevel.tick}
     */
    public static void dispatchLevelTick(ServerLevel level, java.util.function.BooleanSupplier haveTime) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) {
            // mode = "off" (or the runtime is not up yet): plain Vanilla.
            level.tick(haveTime);
            return;
        }
        java.util.function.BooleanSupplier pump = net.multiforge.neoforge.chunk.MainThreadHandoff.pumpFor(level.getServer());
        if (level.dimension() == Level.OVERWORLD) {
            checkOverrun(MultiThreadedSchedulerHost.GLOBAL_WORLD, host.driveGlobalTick(DISPATCH_DEADLINE_NANOS, pump));
        }

        level.tick(haveTime);

        WorldRef world = asWorldRef(level);
        if (host.regionizerForOrNull(world) == null) {
            // No chunk of this level is loaded yet; the regionsHandle* guards
            // reported false, so ServerLevel.tick above ran everything inline.
            LevelTickDispatchProbes.noRegionizerInline(world.dimensionId());
            return;
        }
        // A throwable from a region's tick is rethrown here, after every region
        // finished, so MinecraftServer.tickChildren's "Exception ticking world"
        // crash handling applies exactly as for Vanilla's inline level tick.
        checkOverrun(world, host.driveRegions(world, DISPATCH_DEADLINE_NANOS, pump));
        // Entities no region ticked (outside every region, or moved across a
        // region border mid-tick).
        level.mfTickEntitiesAfterRegions();
        // Chunk work a region could not take this tick, then the block-change
        // broadcast Vanilla sends right after its chunk loop.
        level.getChunkSource().mfAfterRegions();
    }

    /**
     * Whether {@code level}'s random ticks and natural spawning run in its
     * regions this tick ({@code ServerChunkCache.tickChunks} then queues them
     * per region instead of running them inline).
     */
    public static boolean regionsHandleChunkTicks(ServerLevel level) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        return host != null && host.regionizerForOrNull(asWorldRef(level)) != null;
    }

    /** The id of the region owning chunk ({@code chunkX}, {@code chunkZ}) of {@code level}, or -1. */
    public static long regionIdAt(ServerLevel level, int chunkX, int chunkZ) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return -1L;
        net.multiforge.runtime.region.ThreadedRegionizer regionizer = host.regionizerForOrNull(asWorldRef(level));
        if (regionizer == null) return -1L;
        net.multiforge.runtime.region.Region region = regionizer.regionAtChunk(chunkX, chunkZ);
        return region == null ? -1L : region.id().value();
    }

    private static void checkOverrun(WorldRef world, TickRegionScheduler.TickAllResult result) {
        if (result.allCompleted()) return;
        ProbeRegistry.bump("region-tick.dispatch.overrun");
        String msg = "level " + world.dimensionId() + " tick barrier: "
                + result.overrunRegions().size() + "/" + result.regionCount()
                + " regions did not complete within "
                + (DISPATCH_DEADLINE_NANOS / 1_000_000L) + "ms — first overrun: "
                + result.overrunRegions().get(0);
        if (RegionTickWatchdog.mode() == RegionTickWatchdog.Mode.STRICT) {
            throw new RegionDispatchOverrunException(world, result);
        }
        ViolationLogger.warn("region-tick.dispatch.overrun", msg);
    }

    /**
     * B3.2 (docs/design/m13-b3-region-tick.md §5.1): {@code true} iff the
     * {@code BLOCK_FLUID_TICKS} phase slot is actually handling {@code
     * level}'s scheduled block/fluid ticks this tick — i.e. the MultiForge
     * runtime is installed, {@code level}'s world has a materialised
     * regionizer, and a real {@link
     * net.multiforge.runtime.region.ScheduledTickRunner} has been
     * registered (see {@code RegionRuntimeInit.install} /
     * {@code net.multiforge.neoforge.tick.ScheduledTickRunnerBridge}).
     * Read by the {@code ServerLevel.tick(BooleanSupplier)} patch hunk to
     * decide whether to skip Vanilla's inline {@code
     * blockTicks.tick}/{@code fluidTicks.tick} pair — when this returns
     * {@code false} (bootstrap, no regionizer yet, or no runner
     * registered), the Vanilla-inline path stays live so ticks are never
     * silently dropped (CLAUDE.md rule 5).
     */
    public static boolean regionsHandleBlockFluidTicks(ServerLevel level) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return false;
        if (host.regionizerForOrNull(asWorldRef(level)) == null) return false;
        return host.hasBlockFluidRunner();
    }

    /**
     * B3.4 (docs/design/m13-b3-region-tick.md §5.3): {@code true} iff
     * {@code level} is a {@link ServerLevel} the MultiForge runtime is
     * installed for <em>and</em> {@link
     * net.multiforge.neoforge.tick.BlockEntityTickerBridge#installOnLevel}
     * has run for its world. Consulted by two of the
     * {@code 02-region-tick/net/minecraft/world/level/Level.java.patch}
     * hunks:
     *
     * <ul>
     * <li>{@code Level.addBlockEntityTicker} — a newly added ticker is
     * routed into its owning region's {@code
     *       HolderManagerRegionData.blockEntityTickers} slice only when
     * this returns {@code true}; otherwise it stays purely on the
     * Vanilla-inline list (the ordinary pre-B3.4 behaviour).</li>
     * <li>{@code Level.tickBlockEntities()} — the entire inline
     * iteration is skipped when this returns {@code true}, because
     * {@code MultiThreadedSchedulerHost}'s per-region {@code
     *       BLOCK_ENTITIES} phase body does that work instead. Ticking
     * both would double-tick every block entity in the world.</li>
     * </ul>
     *
     * <p>Both call sites reading the same flag is what keeps them
     * consistent with each other — see {@code BlockEntityTickerBridge}'s
     * class javadoc for why a ticker added before installation can never
     * fall into the permanent gap of "not on the inline list's tick path
     * (guard flipped true) and not on any region's list (never
     * bridged)."
     */
    public static boolean regionsHandleBlockEntities(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        if (MultiForgeRegionizedRuntime.current() == null) return false;
        return net.multiforge.neoforge.tick.BlockEntityTickerBridge.isInstalled(serverLevel);
    }

    /**
     * Convert a vanilla {@link ServerLevel} to a {@link WorldRef} — the
     * public API's dimension identifier. Kept here rather than in
     * {@link WorldRef} itself because {@code WorldRef} is in
     * multiforge-api and must not depend on Minecraft classes.
     */
    public static WorldRef asWorldRef(ServerLevel level) {
        return WorldRef.of(level.dimension().location().toString());
    }

    /**
     * B3.3 (docs/design/m13-b3-region-tick.md §5.2): the {@code
     * ServerLevel.tick}/{@code mfTickEntitiesAll} no-op guard: the
     * check is host-installed + regionizer-materialised + runner-
     * registered (one {@link
     * net.multiforge.runtime.region.EntityTickRunner} host-wide).
     *
     * @return {@code true} iff (1) the MultiForge runtime is installed,
     *         (2) a regionizer has been materialised for {@code level}'s
     *         world, and (3) a real (non-default) {@link
     *         net.multiforge.runtime.region.EntityTickRunner} has been
     *         bound via {@code MultiThreadedSchedulerHost.setEntityTickRunner}
     *         — in which case the patched {@code ServerLevel.tick}'s
     *         Vanilla-inline entity pass must be skipped, because the
     *         {@code ENTITY_AI} phase body ({@code phaseEntityAiTick})
     *         already ticks every owned chunk's entities from each
     *         region's own worker thread. {@code false} means the Vanilla-
     *         inline fallback ({@code ServerLevel.mfTickEntitiesAll}) must
     *         still run, exactly like the pre-B3.3 behaviour.
     */
    public static boolean regionsHandleEntityTicks(ServerLevel level) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return false;
        if (host.regionizerForOrNull(asWorldRef(level)) == null) return false;
        return host.hasEntityTickRunner();
    }

    /**
     * Thrown by {@link #dispatchLevelTick} only when
     * {@link RegionTickWatchdog.Mode#STRICT} is active and one or more
     * regions overran the barrier deadline. In the default
     * {@link RegionTickWatchdog.Mode#WARN warn} mode, the coordinator
     * logs + bumps the probe and continues — this class is never
     * instantiated in production runs. Mirrors
     * {@link RegionTickWatchdog.RegionTickOverrunException}'s shape so
     * strict-mode assertion code can treat the two symmetrically.
     */
    public static final class RegionDispatchOverrunException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String worldId;
        private final int regionCount;
        private final int overrunCount;

        RegionDispatchOverrunException(WorldRef world, TickRegionScheduler.TickAllResult result) {
            super("Level " + world.dimensionId() + " tick dispatch: "
                    + result.overrunRegions().size() + "/" + result.regionCount()
                    + " regions did not complete within the dispatch deadline");
            this.worldId = world.dimensionId();
            this.regionCount = result.regionCount();
            this.overrunCount = result.overrunRegions().size();
        }

        public String worldId() {
            return worldId;
        }

        public int regionCount() {
            return regionCount;
        }

        public int overrunCount() {
            return overrunCount;
        }
    }
}
