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
package net.multiforge.runtime.region;

/**
 * MC-free abstraction for the per-region {@code BLOCK_FLUID_TICKS} phase
 * body (docs/design/m13-b3-region-tick.md §5.1). Vanilla's scheduled
 * block/fluid ticks ({@code Level.getBlockTicks().tick(...)} /
 * {@code Level.getFluidTicks().tick(...)}) are drained per owned chunk
 * rather than once per level; a real implementation of this interface
 * necessarily depends on {@code net.minecraft.*} types, so it cannot
 * live in this MC-free module.
 *
 * <p>The production implementation is {@code
 * net.multiforge.neoforge.tick.ScheduledTickRunnerBridge} in the fork
 * module (registered via {@link
 * net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost#setBlockFluidRunner})
 * — it walks {@link Region#ownedChunkSnapshot()} and, for each owned
 * chunk, drains only the scheduled block/fluid ticks scoped to that
 * chunk via {@code ServerLevel.mfTickBlockFluidTicksForChunk}. Until a
 * runner is registered, {@link
 * net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost}'s default
 * is a no-op, so the phase silently does nothing rather than throwing —
 * the Vanilla-inline fallback in {@code
 * net.multiforge.neoforge.RegionizedTickCoordinator}/{@code ServerLevel}
 * still drives block/fluid ticks in that case (CLAUDE.md rule 5).
 */
@FunctionalInterface
public interface ScheduledTickRunner {

    /**
     * Drain the scheduled block/fluid ticks owned by {@code region} —
     * i.e. whose position falls inside one of {@link
     * Region#ownedChunkSnapshot()}'s chunks. Must not block (CLAUDE.md
     * rule 4); runs on the region's own worker thread. Any exception
     * this throws is caught by the caller (see {@code
     * MultiThreadedSchedulerHost#phaseBlockFluidTicksTick}) and turned
     * into a rate-limited warning rather than propagating — a
     * misbehaving runner must never strand the rest of that tick's
     * phases (CLAUDE.md rule 5).
     */
    void runBlockFluidTicks(Region region);
}
