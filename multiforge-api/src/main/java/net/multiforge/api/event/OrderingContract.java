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
