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
package net.multiforge.runtime.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshot of the operator-visible knobs from
 * {@code multiforge-server.toml}. Every field is immutable; a change
 * from an in-game command produces a new snapshot published to
 * {@link MultiForgeConfigStore} subscribers.
 *
 * <p>Layout:
 * <pre>
 *   [mtserver]
 *   cores = 8               # cores × threadsPerCore = tick worker count
 *   threadsPerCore = 2
 *   mode = "hybrid"         # off | hybrid | strict
 *
 *   [region]
 *   size = 4                # log2 of chunks per section side (0..8); 4 = 16 chunks
 *
 *   [violations]
 *   policy = "warn"         # warn | reroute-only | fail
 *   warnPerMin = 5          # warnings per minute per violation site
 *
 *   [tick]
 *   inlineSingleRegion = true         # a level with one region ticks it on the server thread
 *   serialLaneHotWaitMs = 5           # serial-lane hand-off time per tick (ms) that moves a
 *                                     # region to the server thread; 0 = never
 *
 *   [entities]
 *   deferVisibility = true            # hold entity-visibility changes back while workers run
 *
 *   [perf]
 *   lockFreeOutsidePhase = true       # server-thread entity queries skip the storage lock
 *                                     # while no region worker runs
 * </pre>
 * A file written by an older version lacks the newer keys; they take their defaults.
 */
public record MultiForgeConfig(
        int cores,
        int threadsPerCore,
        Mode mode,
        int regionSize,
        ViolationPolicy violationPolicy,
        int warnPerMin,
        boolean inlineSingleRegion,
        int serialLaneHotWaitMs,
        boolean deferVisibility,
        boolean lockFreeOutsidePhase) {

    public static final boolean DEFAULT_INLINE_SINGLE_REGION = true;
    public static final int DEFAULT_SERIAL_LANE_HOT_WAIT_MS = 5;
    public static final boolean DEFAULT_DEFER_VISIBILITY = true;
    public static final boolean DEFAULT_LOCK_FREE_OUTSIDE_PHASE = true;

    /** The pre-{@code [perf]} layout; the perf knobs take their defaults. */
    public MultiForgeConfig(
            int cores,
            int threadsPerCore,
            Mode mode,
            int regionSize,
            ViolationPolicy violationPolicy,
            int warnPerMin,
            boolean inlineSingleRegion,
            int serialLaneHotWaitMs,
            boolean deferVisibility) {
        this(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                violationPolicy,
                warnPerMin,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                deferVisibility,
                DEFAULT_LOCK_FREE_OUTSIDE_PHASE);
    }

    /** The pre-{@code [entities]} layout; the entity knobs take their defaults. */
    public MultiForgeConfig(
            int cores,
            int threadsPerCore,
            Mode mode,
            int regionSize,
            ViolationPolicy violationPolicy,
            int warnPerMin,
            boolean inlineSingleRegion,
            int serialLaneHotWaitMs) {
        this(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                violationPolicy,
                warnPerMin,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                DEFAULT_DEFER_VISIBILITY);
    }

    /** The pre-{@code [tick]} layout; the tick placement knobs take their defaults. */
    public MultiForgeConfig(
            int cores, int threadsPerCore, Mode mode, int regionSize, ViolationPolicy violationPolicy, int warnPerMin) {
        this(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                violationPolicy,
                warnPerMin,
                DEFAULT_INLINE_SINGLE_REGION,
                DEFAULT_SERIAL_LANE_HOT_WAIT_MS);
    }

    public enum Mode {
        OFF,
        HYBRID,
        STRICT
    }

    /**
     * What happens when code on a region worker mutates a chunk its region
     * does not own (see {@code OwnershipEnforcer}).
     */
    public enum ViolationPolicy {
        /** Reroute the mutation to the owning region and log a rate-limited warning. */
        WARN,
        /** Reroute silently (the probe counter still records it). */
        REROUTE_ONLY,
        /** Throw — the same as {@code mode = "strict"} for ownership. */
        FAIL
    }

    private static final Logger LOG = LoggerFactory.getLogger("multiforge.config");

    /**
     * Set once the first time {@link #tickWorkerCount()} honours the
     * {@code -Dmultiforge.workers=N} override, so the "override active"
     * notice is logged exactly once per JVM rather than once per tick.
     */
    private static final AtomicBoolean WORKERS_OVERRIDE_LOGGED = new AtomicBoolean(false);

    /**
     * Worker count for the tick-region scheduler. {@code cores *
     * threadsPerCore} from {@code multiforge.toml}, unless
     * {@code -Dmultiforge.workers=N} is set on the JVM command line, in
     * which case that value short-circuits the TOML-derived computation
     * entirely (see Phase 7 runbook §7 — lets 7.3's N-worker verification
     * run flex worker count without editing {@code multiforge.toml}).
     *
     * <p>An unparsable or non-positive override (blank, non-numeric,
     * zero, negative) is not a meaningful worker count, so it silently
     * falls through to the normal {@code cores * threadsPerCore}
     * computation rather than throwing or clamping to 1.
     */
    public int tickWorkerCount() {
        String override = System.getProperty("multiforge.workers");
        if (override != null && !override.isBlank()) {
            try {
                int n = Integer.parseInt(override.trim());
                if (n > 0) {
                    if (WORKERS_OVERRIDE_LOGGED.compareAndSet(false, true)) {
                        LOG.info(
                                "multiforge.workers override active: using {} tick worker(s) "
                                        + "instead of the computed cores({}) * threadsPerCore({})",
                                n,
                                cores,
                                threadsPerCore);
                    }
                    return n;
                }
                // 0 or negative — not a meaningful worker count, fall through.
            } catch (NumberFormatException ignored) {
                // Invalid value — fall through to the computed default.
            }
        }
        return Math.max(1, cores * threadsPerCore);
    }

    /**
     * The mode the server actually runs in: {@code -Dmultiforge.mode=off|hybrid|strict}
     * when set (so an operator can switch MultiForge off, or into strict
     * regression mode, without editing the file), otherwise {@link #mode()}.
     * An unrecognised property value is ignored.
     *
     * <ul>
     * <li>{@link Mode#OFF} — the regionized runtime is not installed; the
     *     server runs Vanilla's single-threaded tick.</li>
     * <li>{@link Mode#HYBRID} — regions tick in parallel; an ownership
     *     violation is rerouted to its owner with a rate-limited warning.</li>
     * <li>{@link Mode#STRICT} — as hybrid, but ownership violations and
     *     region-tick overruns throw (regression runs).</li>
     * </ul>
     */
    public Mode effectiveMode() {
        String override = System.getProperty("multiforge.mode");
        if (override != null && !override.isBlank()) {
            try {
                return Mode.valueOf(override.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                LOG.warn("ignoring unrecognised -Dmultiforge.mode={} (expected off, hybrid or strict)", override);
            }
        }
        return mode;
    }

    public static MultiForgeConfig defaults() {
        return new MultiForgeConfig(
                Runtime.getRuntime().availableProcessors(),
                1,
                Mode.HYBRID,
                4, // 2^4 = 16 chunks per section side (Folia default)
                ViolationPolicy.WARN,
                5);
    }

    /** Builder-style with-methods so /multiforge commands can produce a new snapshot. */
    public MultiForgeConfig withCores(int v) {
        return new MultiForgeConfig(
                v,
                threadsPerCore,
                mode,
                regionSize,
                violationPolicy,
                warnPerMin,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                deferVisibility,
                lockFreeOutsidePhase);
    }

    public MultiForgeConfig withThreadsPerCore(int v) {
        return new MultiForgeConfig(
                cores,
                v,
                mode,
                regionSize,
                violationPolicy,
                warnPerMin,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                deferVisibility,
                lockFreeOutsidePhase);
    }

    public MultiForgeConfig withMode(Mode v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                v,
                regionSize,
                violationPolicy,
                warnPerMin,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                deferVisibility,
                lockFreeOutsidePhase);
    }

    public MultiForgeConfig withRegionSize(int v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                mode,
                v,
                violationPolicy,
                warnPerMin,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                deferVisibility,
                lockFreeOutsidePhase);
    }

    public MultiForgeConfig withViolationPolicy(ViolationPolicy v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                v,
                warnPerMin,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                deferVisibility,
                lockFreeOutsidePhase);
    }

    public MultiForgeConfig withWarnPerMin(int v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                violationPolicy,
                v,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                deferVisibility,
                lockFreeOutsidePhase);
    }

    public MultiForgeConfig withInlineSingleRegion(boolean v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                violationPolicy,
                warnPerMin,
                v,
                serialLaneHotWaitMs,
                deferVisibility,
                lockFreeOutsidePhase);
    }

    public MultiForgeConfig withSerialLaneHotWaitMs(int v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                violationPolicy,
                warnPerMin,
                inlineSingleRegion,
                v,
                deferVisibility,
                lockFreeOutsidePhase);
    }

    public MultiForgeConfig withDeferVisibility(boolean v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                violationPolicy,
                warnPerMin,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                v,
                lockFreeOutsidePhase);
    }

    public MultiForgeConfig withLockFreeOutsidePhase(boolean v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                violationPolicy,
                warnPerMin,
                inlineSingleRegion,
                serialLaneHotWaitMs,
                deferVisibility,
                v);
    }

    /**
     * {@code entities.deferVisibility}, unless {@code
     * -Dmultiforge.entities.deferVisibility=true|false} overrides it: while
     * region workers run, entity-visibility changes from chunk promotions and
     * demotions are held back and replayed at the barrier ({@code
     * RegionPhase}). The kill switch for that deferral.
     */
    public boolean effectiveDeferVisibility() {
        String override = System.getProperty("multiforge.entities.deferVisibility");
        if (override != null && !override.isBlank()) return Boolean.parseBoolean(override.trim());
        return deferVisibility;
    }

    /**
     * {@code perf.lockFreeOutsidePhase}, unless {@code
     * -Dmultiforge.perf.lockFreeOutsidePhase=true|false} overrides it: entity
     * queries on the server thread while no region worker runs read the entity
     * storage directly, without its lock or a copy ({@code LockingEntityGetter}).
     * The kill switch for that fast path.
     */
    public boolean effectiveLockFreeOutsidePhase() {
        String override = System.getProperty("multiforge.perf.lockFreeOutsidePhase");
        if (override != null && !override.isBlank()) return Boolean.parseBoolean(override.trim());
        return lockFreeOutsidePhase;
    }
}
