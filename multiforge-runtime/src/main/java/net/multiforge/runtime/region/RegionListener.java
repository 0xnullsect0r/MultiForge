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
 * Callbacks invoked by {@link ThreadedRegionizer} at structural changes
 * to the region graph. Subsystems that maintain per-region state — the
 * chunk holder manager, the task queue, per-world data slots
 * ({@link RegionizedData}), the tick scheduler — register a listener
 * so they can fold, peel, or discard their side state atomically with
 * the regionizer's own mutation.
 *
 * <p>All callbacks run under the regionizer's write lock, so listeners
 * see a fully consistent {@code sectionToRegion} snapshot and never see
 * partially-committed merges or splits. Listeners must therefore not
 * block, take other locks that could be held by tick workers, or call
 * back into the regionizer.
 *
 * <p>All methods default to no-ops so listeners can override only what
 * they care about.
 */
public interface RegionListener {

    /**
     * A new region was just published (transient → ready). Fired by
     * both {@link ThreadedRegionizer#addChunk(net.multiforge.api.world.ChunkPos)}
     * (when no adjacent region existed) and by the split path when a
     * disconnected component peels off into a fresh region.
     */
    default void onRegionCreated(Region region) {}

    /**
     * A merge is about to happen: {@code dying}'s sections and any side
     * state should be folded into {@code surviving}. Fired before the
     * regionizer moves sections; both regions are still valid to
     * observe here. {@code dying} is marked DEAD immediately after all
     * listeners have been notified.
     */
    default void onRegionsMerging(Region surviving, Region dying) {}

    /**
     * A split just produced a fresh region peeled off from {@code
     * source}. Called once per new child region; {@code child} already
     * owns its sections at this point.
     */
    default void onRegionSplit(Region source, Region child) {}

    /**
     * A region has become DEAD (either because its last section was
     * removed or because it was merged into another). Listeners must
     * drop any side state keyed by this region's id. Called after
     * {@link #onRegionsMerging} for merges — do not fold state here,
     * only clean up.
     */
    default void onRegionDied(Region region) {}
}
