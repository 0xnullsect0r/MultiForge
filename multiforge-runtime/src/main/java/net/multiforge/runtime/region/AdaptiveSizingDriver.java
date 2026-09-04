/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import net.multiforge.api.world.ChunkPos;

/**
 * Runs off the tick loop on a low-priority thread. Watches per-region
 * MSPT and player positions; issues split hints to {@link
 * ThreadedRegionizer} when hot; issues merge hints when cold and
 * player-free.
 *
 * <p>M2 slice: the driver computes the recommendations and exposes
 * them through {@link SizingRecommendation}. The M2.5 patch wires the
 * recommendations into the regionizer with the concrete split/merge
 * primitives once the chunk system landing gates permit it. Until
 * then, the driver is pure and unit-testable.
 */
public final class AdaptiveSizingDriver {

    /** Snapshot of a region for sizing decisions. */
    public record RegionSample(RegionId id, double meanMspt, double p95Mspt, int sectionCount, int nearbyPlayerCount) {}

    public enum Recommendation {
        HOLD,
        SPLIT,
        MERGE,
        PARK,
    }

    public record SizingRecommendation(RegionId id, Recommendation recommendation, String reason) {}

    /** Config knobs — sane defaults matched to a 20-TPS budget of 50 ms per tick. */
    public record Config(
            double splitMsptThreshold, double mergeMsptThreshold, int minSectionsForSplit, boolean playerOnlyMode) {

        public static Config defaults() {
            return new Config(35.0, 5.0, 4, true);
        }
    }

    private final Config config;

    public AdaptiveSizingDriver(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    /**
     * Produce one recommendation per region.
     *
     * @param samples snapshot from the tick loop (called from a
     *                background thread; the sample is stable within
     *                the call)
     */
    public List<SizingRecommendation> recommend(Collection<RegionSample> samples) {
        return samples.stream().map(this::recommendFor).toList();
    }

    private SizingRecommendation recommendFor(RegionSample s) {
        if (s.p95Mspt >= config.splitMsptThreshold && s.sectionCount >= config.minSectionsForSplit) {
            return new SizingRecommendation(
                    s.id,
                    Recommendation.SPLIT,
                    "p95 MSPT " + fmt(s.p95Mspt) + "ms ≥ " + fmt(config.splitMsptThreshold));
        }
        if (config.playerOnlyMode && s.nearbyPlayerCount == 0) {
            return new SizingRecommendation(s.id, Recommendation.PARK, "player-only mode + no nearby players");
        }
        if (s.meanMspt <= config.mergeMsptThreshold && s.nearbyPlayerCount == 0) {
            return new SizingRecommendation(
                    s.id,
                    Recommendation.MERGE,
                    "mean MSPT " + fmt(s.meanMspt) + "ms ≤ " + fmt(config.mergeMsptThreshold) + " + no players");
        }
        return new SizingRecommendation(s.id, Recommendation.HOLD, "in budget");
    }

    /** Convenience for producers that already have a supplier per region. */
    public static RegionSample sampleFrom(
            RegionId id, RegionMspt mspt, int sectionCount, Supplier<Integer> nearbyPlayerCountSupplier) {
        return new RegionSample(
                id, mspt.averageMillis(), mspt.percentileMillis(0.95), sectionCount, nearbyPlayerCountSupplier.get());
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /** Marker for tests that want a stable player-count → 0 supplier. */
    public static Supplier<Integer> noPlayers() {
        return () -> 0;
    }

    /** Adapter for a region occupied by n players. */
    public static Supplier<Integer> playerCount(int n) {
        return () -> n;
    }

    /** Placeholder for the chunk→player-radius query the M3 patch supplies. */
    @FunctionalInterface
    public interface PlayerLocator {
        int nearbyPlayerCount(ChunkPos anchor, int radiusChunks);
    }
}
