/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

/**
 * Lifecycle state of a {@link Region}. Matches Folia's
 * {@code ThreadedRegionizer.ThreadedRegion.State}.
 *
 * <pre>
 *   TRANSIENT ──► READY ──┬─► TICKING ──► READY   (cycle)
 *                         │                ▲
 *                         ├─► FOLDING ─────┤       (merge-quiesce)
 *                         │                │
 *                         └────────────────┘
 *                            │
 *                            ▼
 *                          DEAD (terminal, after merge or region drop)
 * </pre>
 */
public enum RegionState {
    /** Just constructed; not yet visible to the tick scheduler. */
    TRANSIENT,

    /** Fully constructed and eligible for scheduling. */
    READY,

    /** Currently being ticked by a worker thread. Cannot grow. */
    TICKING,

    /**
     * Quiescing for a merge: another region is folding its side state into
     * this region under the regionizer write lock. {@link Region#tryMarkTicking()}
     * refuses this state so the surviving region cannot start a tick while
     * merge listeners mutate its slot values. Transient — returns to
     * {@link #READY} once the merge completes. Phase 1 task 1.1.
     */
    FOLDING,

    /** Merged into another region, or removed. Terminal. */
    DEAD,
}
