/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
 *   size = 16               # chunks per section side (2^n where 0..8)
 *   mode = "player-only"    # player-only | full-world
 *   msptSplitThreshold = 35.0
 *   msptMergeThreshold = 5.0
 *
 *   [violations]
 *   policy = "warn"         # warn | reroute-only | fail
 *   warnPerMin = 5
 * </pre>
 */
public record MultiForgeConfig(
        int cores,
        int threadsPerCore,
        Mode mode,
        int regionSize,
        RegionMode regionMode,
        double msptSplitThreshold,
        double msptMergeThreshold,
        ViolationPolicy violationPolicy,
        int warnPerMin) {

    public enum Mode {
        OFF,
        HYBRID,
        STRICT
    }

    public enum RegionMode {
        PLAYER_ONLY,
        FULL_WORLD
    }

    public enum ViolationPolicy {
        WARN,
        REROUTE_ONLY,
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

    public static MultiForgeConfig defaults() {
        return new MultiForgeConfig(
                Runtime.getRuntime().availableProcessors(),
                1,
                Mode.HYBRID,
                4, // 2^4 = 16 chunks per section side (Folia default)
                RegionMode.PLAYER_ONLY,
                35.0,
                5.0,
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
                regionMode,
                msptSplitThreshold,
                msptMergeThreshold,
                violationPolicy,
                warnPerMin);
    }

    public MultiForgeConfig withThreadsPerCore(int v) {
        return new MultiForgeConfig(
                cores,
                v,
                mode,
                regionSize,
                regionMode,
                msptSplitThreshold,
                msptMergeThreshold,
                violationPolicy,
                warnPerMin);
    }

    public MultiForgeConfig withMode(Mode v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                v,
                regionSize,
                regionMode,
                msptSplitThreshold,
                msptMergeThreshold,
                violationPolicy,
                warnPerMin);
    }

    public MultiForgeConfig withRegionSize(int v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                mode,
                v,
                regionMode,
                msptSplitThreshold,
                msptMergeThreshold,
                violationPolicy,
                warnPerMin);
    }

    public MultiForgeConfig withRegionMode(RegionMode v) {
        return new MultiForgeConfig(
                cores,
                threadsPerCore,
                mode,
                regionSize,
                v,
                msptSplitThreshold,
                msptMergeThreshold,
                violationPolicy,
                warnPerMin);
    }
}
