/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.config;

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

    public int tickWorkerCount() {
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
