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
import net.minecraft.world.level.Level;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionTickWatchdog;
import net.multiforge.runtime.region.ThreadedRegionizer;
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
 * <p><b>Post-B3 state (v1.3.0):</b> {@link #dispatchLevelTick} fans
 * out to per-region workers via
 * {@link TickRegionScheduler#tickAll(Collection, long)} as the
 * synchronisation barrier for any regions currently mid-tick on the
 * worker pool, and does nothing else — there is no trailing inline
 * invocation of Vanilla's per-level tick body any more. Every
 * constituent of that former call now has a real per-region (or
 * global-region) destination: the eight migrated global subsystems
 * (weather, time, world-border, scoreboard, bossbars, raids, dragon-
 * fight, command-dispatch) route through {@code GlobalSystemsBridge}'s
 * {@code xxxReady()} guards (M5), and the three residual per-level
 * pieces — scheduled block/fluid ticks, entity AI, and block-entity
 * ticking — route through the {@code BLOCK_FLUID_TICKS}, {@code
 * ENTITY_AI}, and {@code BLOCK_ENTITIES} phase bodies wired in
 * {@link MultiThreadedSchedulerHost} (B3.2, B3.3, B3.4 respectively).
 * See {@code docs/design/m13-b3-region-tick.md} for the full migration
 * plan and {@code docs/design/global-region.md} §6.4 for the frozen
 * target shape this method matches verbatim.
 *
 * <p>Because there is nothing left for an inline Vanilla fallback to
 * cover, the three failure paths — bootstrap (runtime not installed
 * yet), no-regionizer (world has no materialised regions), and
 * dispatch-side failure ({@link TickRegionScheduler#tickAll} itself
 * throwing) — no longer run Vanilla's per-level body inline. Instead
 * each bumps a named {@link ProbeRegistry} counter and emits a rate-
 * limited {@link ViolationLogger#warn}, then returns: the server tick
 * counter still advances, but no per-region work happens for that
 * level this tick. The bootstrap, no-regionizer, and dispatch-failure
 * paths delegate their probe/warn pair to {@link LevelTickDispatchProbes},
 * a pure-Java helper that is directly unit-testable without a Minecraft
 * classpath. This is a deliberate contract change from the
 * pre-B3.5 shape, not a silent regression — CLAUDE.md rule 5 ("auto-
 * reroute + warn is the default... never throw... reroute the call and
 * log a rate-limited warning") is satisfied because every one of these
 * paths warns loudly and is visible via the probe registry; the
 * "reroute" at this level of the stack is "advance the server tick
 * without per-region work" rather than "fall back to running Vanilla,"
 * because a full inline Vanilla re-run would itself race the real
 * per-region bodies that now concurrently touch the same chunks,
 * entities, and block entities from worker threads (see {@code
 * docs/design/m13-b3-region-tick.md} §7's correctness invariants).
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
     * Dispatch one unit of per-level tick work for {@code level} — the
     * fork-local replacement for vanilla's {@code serverlevel.tick(p)}
     * call.
     *
     * <p>Flow:
     * <ol>
     * <li>If the MultiForge runtime is not yet installed (fresh boot,
     * pre-{@code ServerAboutToStart}), bump {@code
     * region-tick.bootstrap-skip} and warn — the server tick counter
     * advances but nothing is ticked for {@code level} this pass.</li>
     * <li>If no regionizer has been materialised for {@code level}'s
     * world yet (no {@code ChunkEvent.Load} has fired, or the world is
     * exiting), bump {@code region-tick.no-regionizer-skip} and warn,
     * same shape.</li>
     * <li>Otherwise, snapshot the world's live regions and invoke
     * {@link TickRegionScheduler#tickAll(Collection, long)} as the
     * synchronisation barrier for any regions currently mid-tick on
     * the worker pool. Each region's own {@code PhasedRegionTickBody}
     * — including the BLOCK_FLUID_TICKS (B3.2), ENTITY_AI (B3.3), and
     * BLOCK_ENTITIES (B3.4) phase bodies — does the actual per-level
     * work formerly run inline here.</li>
     * <li>If any region overruns the dispatch deadline, route to the
     * strict-mode-vs-warn path: in
     * {@link RegionTickWatchdog.Mode#STRICT STRICT} mode
     * ({@code -Dmultiforge.regiontick.strict=on}), throw; otherwise
     * (the default) rate-limited warn + probe bump + continue,
     * matching CLAUDE.md rule 5's "auto-reroute + warn" default.</li>
     * <li>If the fan-out itself throws (dispatch-side bug — never a
     * region worker's own exception, which stays on the worker), bump
     * {@code region-tick.dispatch.failure} and warn, then return. This
     * tick is skipped for {@code level}; there is no inline fallback
     * left to run.</li>
     * </ol>
     *
     * <p>Unlike the pre-B3.5 shape, there is no trailing unconditional
     * call of any kind: once BLOCK_FLUID_TICKS, ENTITY_AI, and
     * BLOCK_ENTITIES are wired (B3.2-B3.4) alongside the eight M5
     * global subsystems, every constituent of vanilla's per-level tick
     * body has a real per-region (or global-region) destination and
     * nothing remains for an inline-Vanilla-fallback call to do. See
     * {@code docs/design/m13-b3-region-tick.md} and {@code
     * docs/design/global-region.md} §6.4 (the frozen target shape this
     * method matches).
     *
     * @param level the level being ticked; the coordinator looks up its
     *              regionizer via
     *              {@link MultiThreadedSchedulerHost#regionizerForOrNull}.
     */
    public static void dispatchLevelTick(ServerLevel level) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) {
            // Bootstrap: runtime not installed yet. Not silent — CLAUDE.md
            // rule 5 requires visible auto-reroute+warn, not a swallowed
            // skip. The server tick counter still advances; this level
            // simply gets no per-region work this pass.
            LevelTickDispatchProbes.bootstrapSkip(level.dimension().location().toString());
            return;
        }
        WorldRef world = asWorldRef(level);
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) {
            // No regions materialised for this world yet. Same shape as the
            // bootstrap path above — nothing to synchronise against, so
            // warn + skip rather than silently doing nothing.
            LevelTickDispatchProbes.noRegionizerSkip(world.dimensionId());
            return;
        }

        Collection<Region> regions = regionizer.regions();
        TickRegionScheduler.TickAllResult result;
        try {
            result = host.scheduler().tickAll(regions, DISPATCH_DEADLINE_NANOS);
        } catch (Throwable t) {
            // Auto-reroute+warn (CLAUDE.md rule 5): a dispatch-side failure
            // bumps the probe and warns, then returns — there is no inline
            // Vanilla fallback left to run post-B3.5. Region-worker
            // exceptions are caught inside the worker loop and never reach
            // here.
            LevelTickDispatchProbes.dispatchFailure(world.dimensionId(), t);
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
     * B3.4 (docs/design/m13-b3-region-tick.md §5.3): {@code true} iff
     * {@code level} is a {@link ServerLevel} the MultiForge runtime is
     * installed for <em>and</em> {@link
     * net.multiforge.neoforge.tick.BlockEntityTickerBridge#installOnLevel}
     * has run for its world. Consulted by two of the
     * {@code 02-region-tick/net/minecraft/world/level/Level.java.patch}
     * hunks:
     *
     * <ul>
     *   <li>{@code Level.addBlockEntityTicker} — a newly added ticker is
     *       routed into its owning region's {@code
     *       HolderManagerRegionData.blockEntityTickers} slice only when
     *       this returns {@code true}; otherwise it stays purely on the
     *       Vanilla-inline list (the ordinary pre-B3.4 behaviour).</li>
     *   <li>{@code Level.tickBlockEntities()} — the entire inline
     *       iteration is skipped when this returns {@code true}, because
     *       {@code MultiThreadedSchedulerHost}'s per-region {@code
     *       BLOCK_ENTITIES} phase body does that work instead. Ticking
     *       both would double-tick every block entity in the world.</li>
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
     * ServerLevel.tick}/{@code mfTickEntitiesAll} no-op guard —
     * mirrors {@code GlobalSystemsBridge.weatherHandled}/{@code
     * timeHandled}'s shape exactly (bound + has-a-target for this
     * level), except the "target" here is host-wide (one {@link
     * net.multiforge.runtime.region.EntityTickRunner}, not one per
     * world) so the check is host-installed + regionizer-materialised
     * + runner-registered rather than a per-world lookup.
     *
     * @return {@code true} iff (1) the MultiForge runtime is installed,
     *     (2) a regionizer has been materialised for {@code level}'s
     *     world, and (3) a real (non-default) {@link
     *     net.multiforge.runtime.region.EntityTickRunner} has been
     *     bound via {@code MultiThreadedSchedulerHost.setEntityTickRunner}
     *     — in which case the patched {@code ServerLevel.tick}'s
     *     Vanilla-inline entity pass must be skipped, because the
     *     {@code ENTITY_AI} phase body ({@code phaseEntityAiTick})
     *     already ticks every owned chunk's entities from each
     *     region's own worker thread. {@code false} means the Vanilla-
     *     inline fallback ({@code ServerLevel.mfTickEntitiesAll}) must
     *     still run, exactly like the pre-B3.3 behaviour.
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
