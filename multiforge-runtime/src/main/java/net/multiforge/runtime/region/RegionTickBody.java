/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

/**
 * The unit of work a {@link TickRegionScheduler} runs each tick per
 * region. In production this is bound by the M2 patch to Vanilla's
 * per-region tick body (block/fluid ticks, entity AI, block entities,
 * etc.). For M2 pre-vendored builds, the tick body is a synthetic
 * workload from the bench harness or a no-op.
 */
@FunctionalInterface
public interface RegionTickBody {
    /** Run one tick for {@code region}. Must not block. */
    void tickOnce(Region region);
}
