/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
 * <p><b>M8 sub-step 6b state:</b> {@link #dispatchLevelTick} now
 * performs real per-region fan-out via
 * {@link TickRegionScheduler#tickAll(Collection, long)} before running
 * the vanilla per-level body. Region workers self-tick asynchronously
 * at ~20 TPS through the scheduler's own worker loop; this façade acts
 * as the <em>synchronisation barrier</em> for that work so the vanilla
 * global portion (weather, time, etc.) — still executed inline on the
 * main server thread — cannot race a region mid-tick. Phase 5 (M11)
 * will migrate the global portion into the synthetic global region and
 * remove the trailing inline call.
 *
 * <p>The per-region tick body itself is still the no-op default bound
 * by {@link MultiThreadedSchedulerHost}, so this dispatch is
 * behaviourally identical to the M8 sub-step 5 pass-through today —
 * the important change is the plumbing: the fan-out, the barrier, and
 * the {@link RegionTickWatchdog#mode() strict-mode} gate all light up
 * so Phase 5 can wire a real per-region body without further edits to
 * this file or its vanilla patch.
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
     * <li>Finally, run {@code vanillaBody} on the caller thread for
     * the global per-level portion (weather, time, wandering-trader
     * spawner, etc.). Phase 5 + M11 will migrate this into the
     * synthetic global region.</li>
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

        // Global portion still runs inline on the main thread (weather,
        // time, wandering trader, etc.). Phase 5 + M11 will migrate this
        // into the synthetic global region.
        vanillaBody.run();
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
