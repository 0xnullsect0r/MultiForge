/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.neoforge;

import java.util.Collection;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionTickWatchdog;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.region.TickRegionScheduler;
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
 * <p><b>Post-M5 state (v1.2.0):</b> {@link #dispatchLevelTick} fans
 * out to per-region workers via
 * {@link TickRegionScheduler#tickAll(Collection, long)} as the
 * synchronisation barrier for any regions currently mid-tick on the
 * worker pool. The trailing inline invocation of Vanilla's per-level
 * body is retained: every migrated global subsystem (weather, time,
 * world-border, scoreboard, bossbars, raids, dragon-fight, command-
 * dispatch) has an {@code xxxReady()}-guarded early-return in its
 * Vanilla method, so those become no-ops here — but the residual per-
 * level work (entity tick loop, block-entity tickers, scheduled block/
 * fluid ticks, chunk-source tick) is still executed by
 * {@code vanillaBody} inline because the BLOCK_FLUID_TICKS + ENTITY_AI
 * phases in {@link MultiThreadedSchedulerHost}'s wired tick body have
 * no production wiring yet.
 *
 * <p>Full migration of the residual entity/block-tick portion into
 * region workers is a follow-up milestone past v1.2.0. The three
 * fallback {@code vanillaBody.run()} calls (bootstrap pre-runtime,
 * no-regionizer, dispatch-side failure) preserve CLAUDE.md rule 5's
 * "auto-reroute + warn" default so a fresh boot, an uninitialised
 * world, or a dispatch-time bug still lets the server tick advance
 * rather than hanging silently.
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
     * Dispatch {@code vanillaBody} — vanilla's {@code serverlevel.tick(p)}
     * call for one {@link ServerLevel} — as one unit of per-level tick
     * work.
     *
     * <p>Flow:
     * <ol>
     * <li>If the MultiForge runtime is not yet installed (fresh boot,
     * pre-{@code ServerAboutToStart}) or no regionizer has been
     * materialised for {@code level}'s world yet, run
     * {@code vanillaBody} inline — the vanilla parity fallback.
     * This preserves Vanilla behaviour at bootstrap.</li>
     * <li>Otherwise, snapshot the world's live regions and invoke
     * {@link TickRegionScheduler#tickAll(Collection, long)} as the
     * synchronisation barrier for any regions currently mid-tick
     * on the worker pool.</li>
     * <li>If any region overruns the dispatch deadline, route to the
     * strict-mode-vs-warn path: in
     * {@link RegionTickWatchdog.Mode#STRICT STRICT} mode
     * ({@code -Dmultiforge.regiontick.strict=on}), throw; otherwise
     * (the default) rate-limited warn + probe bump + continue,
     * matching CLAUDE.md rule 5's "auto-reroute + warn" default.</li>
     * <li>If the fan-out itself throws (dispatch-side bug — never a
     * region worker's own exception, which stays on the worker),
     * warn + fall through to the inline body so the server tick
     * still runs.</li>
     * <li>Finally, run {@code vanillaBody} on the caller thread. The
     * eight migrated global subsystems (weather, time, world-border,
     * scoreboard, bossbars, raids, dragon-fight, command-dispatch)
     * no-op via their {@code xxxReady()} guards, so this executes
     * only the residual per-level work: entity tick loop, block-
     * entity tickers, scheduled block/fluid ticks, chunk-source
     * tick. Full per-region migration of those is deferred past
     * v1.2.0.</li>
     * </ol>
     *
     * @param level       the level being ticked; the coordinator looks up its
     *                    regionizer via
     *                    {@link MultiThreadedSchedulerHost#regionizerForOrNull}.
     * @param vanillaBody the vanilla per-level tick call.
     */
    public static void dispatchLevelTick(ServerLevel level, Runnable vanillaBody) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) {
            // Bootstrap: runtime not installed yet — behave like Vanilla.
            vanillaBody.run();
            return;
        }
        WorldRef world = asWorldRef(level);
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) {
            // No regions materialised for this world yet (no ChunkEvent.Load
            // has fired, or the world is exiting). Nothing to synchronise
            // against — run the vanilla body inline.
            vanillaBody.run();
            return;
        }

        Collection<Region> regions = regionizer.regions();
        TickRegionScheduler.TickAllResult result;
        try {
            result = host.scheduler().tickAll(regions, DISPATCH_DEADLINE_NANOS);
        } catch (Throwable t) {
            // Auto-reroute+warn (CLAUDE.md rule 5): any dispatch-side
            // failure falls back to the inline vanilla body so the
            // server tick still runs. Region-worker exceptions are
            // caught inside the worker loop and never reach here.
            ProbeRegistry.bump("region-tick.dispatch.failure");
            ViolationLogger.warn(
                    "region-tick.dispatch.failure",
                    "tickAll failed for " + world.dimensionId() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            vanillaBody.run();
            return;
        }

        if (!result.allCompleted()) {
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

        // Post-B2 (all eight global subsystems migrated), the sub-calls
        // vanillaBody triggers that ARE covered by GlobalSystemsBridge
        // (weather, time, world-border, scoreboard, bossbars, raids,
        // dragon-fight, command-dispatch) short-circuit via each guard's
        // xxxReady() early-return, so vanillaBody effectively runs only
        // the residual per-level work: `entityTickList.forEach(this::
        // tickNonPassenger)`, `blockEntityTickers.tick()`, per-chunk
        // scheduled block/fluid ticks in `tickChunk(...)`, and
        // `serverChunkCache.tick()`. Region workers do NOT currently run
        // those (the BLOCK_FLUID_TICKS + ENTITY_AI phase slots in
        // MultiThreadedSchedulerHost#installM9WiredTickBody are the
        // no-op default — only tests wire them). Removing this call
        // silently disables entity/block/blockentity ticking on the
        // MultiForge-installed path — /67 round-6 fork B F1 caught this.
        // Full B3 (per-region entity + block-tick wiring) is deferred
        // past v1.2.0 to a follow-up milestone that lands those phases.
        vanillaBody.run();
    }

    /**
     * B3.2 (docs/design/m13-b3-region-tick.md §5.1): {@code true} iff the
     * {@code BLOCK_FLUID_TICKS} phase slot is actually handling {@code
     * level}'s scheduled block/fluid ticks this tick — i.e. the MultiForge
     * runtime is installed, {@code level}'s world has a materialised
     * regionizer, and a real {@link
     * net.multiforge.runtime.region.ScheduledTickRunner} has been
     * registered (see {@code MultiForgeGlobalSystemsInit.install} /
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
     * Convert a vanilla {@link ServerLevel} to a {@link WorldRef} — the
     * public API's dimension identifier. Kept here rather than in
     * {@link WorldRef} itself because {@code WorldRef} is in
     * multiforge-api and must not depend on Minecraft classes.
     */
    public static WorldRef asWorldRef(ServerLevel level) {
        return WorldRef.of(level.dimension().location().toString());
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
