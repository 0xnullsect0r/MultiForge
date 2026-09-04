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

    /** Merged into another region, or removed. Terminal. */
    DEAD,
}
