/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.event;

/**
 * The strongest ordering guarantee a mod requires of the dispatcher.
 * Callers should pick the weakest option that still works — stronger
 * guarantees cost throughput.
 */
public enum OrderingContract {
    /** Events for the same region observe program order. Default. */
    PER_REGION,

    /**
     * Events observe a single total order across the whole server. Very
     * expensive under parallel ticks; use only for cross-region
     * invariants (e.g. auditing).
     */
    GLOBAL_TOTAL,

    /** No ordering promises. Cheapest; suitable for pure metrics/logging. */
    BEST_EFFORT,
}
