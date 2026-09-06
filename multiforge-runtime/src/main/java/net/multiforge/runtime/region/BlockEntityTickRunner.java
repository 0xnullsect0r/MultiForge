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

import java.util.List;
import java.util.function.Function;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.HolderManagerRegionData;
import net.multiforge.runtime.chunk.TickingBlockEntityRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Runs one region's slice of block-entity tickers for a single tick —
 * the region-worker counterpart of Vanilla's {@code
 * Level.tickBlockEntities()} (docs/design/m13-b3-region-tick.md §5.3).
 *
 * <p>A plain functional interface so {@code MultiThreadedSchedulerHost}
 * can wire a test double in unit tests without depending on the real
 * {@link ChunkHolderManager} plumbing. {@link #standard} supplies the
 * production implementation: it walks the ticking region's {@link
 * HolderManagerRegionData#snapshotBlockEntityTickers()} slice
 * (populated by B3.1, folded/peeled across region split/merge — see
 * that method's javadoc) and ticks every live, ready ticker.
 *
 * <p><b>On the region/chunk package boundary.</b> {@link
 * RegionChunkSource} deliberately keeps the {@code region} package free
 * of any dependency on the {@code chunk} package, because {@link
 * Region} itself — foundational, used everywhere — must stay on that
 * side of the boundary. {@code BlockEntityTickRunner} is not
 * foundational infrastructure the same way; it is a single, leaf,
 * B3.4-only orchestration utility invoked from exactly one phase-body
 * call site. This file is therefore a deliberate, contained exception
 * to that one-directional convention, not a precedent for wiring the
 * two packages together generally — every other file in this package
 * still avoids importing {@code chunk}.
 *
 * <p>All-runtime: no {@code net.minecraft.*} dependency anywhere in
 * this file. The fork bridge under {@code net.multiforge.neoforge.tick}
 * (B3.4) supplies the {@link TickingBlockEntityRef} adapter wrapping
 * Vanilla's real {@code TickingBlockEntity}, plus the glue that
 * registers/deregisters tickers against {@link HolderManagerRegionData}
 * as chunks load/unload and block entities are placed/broken.
 */
@FunctionalInterface
public interface BlockEntityTickRunner {

    /**
     * Tick every live, ready block-entity ticker owned by {@code
     * region}. Called from the owning region's worker thread only
     * (the {@code BLOCK_ENTITIES} phase). Implementations must not let
     * a single misbehaving ticker abort the rest of the region's
     * tickers — CLAUDE.md rule 5 ("auto-reroute + warn").
     */
    void tickBlockEntitiesForRegion(Region region);

    /**
     * Production implementation. Resolves {@code region}'s {@link
     * HolderManagerRegionData} via the supplied lookups, snapshots its
     * {@link TickingBlockEntityRef} slice, drops any ticker that
     * reports {@link TickingBlockEntityRef#isRemoved()} (mirroring
     * Vanilla's own {@code iterator.remove()} in {@code
     * tickBlockEntities()}), and ticks every remaining entry that wants
     * to run this tick ({@link TickingBlockEntityRef#shouldTick()}).
     * Each ticker call is individually try/catch-guarded so one broken
     * block entity — a mod bug in a custom tick implementation — can
     * never stop the rest of the region's block entities from ticking
     * this pass (docs/design/m13-b3-region-tick.md §5.3, mirroring
     * Vanilla's own per-ticker isolation).
     *
     * @param worldForRegion resolves the {@link WorldRef} that owns a
     *     given region id; {@code null} when the region has died or no
     *     world has claimed it yet — a no-op tick, not an error.
     * @param chunkManagerForOrNull the non-creating {@code
     *     ChunkHolderManager} lookup for a world; {@code null} when no
     *     chunk manager has been materialised for that world yet.
     */
    static BlockEntityTickRunner standard(
            Function<RegionId, WorldRef> worldForRegion, Function<WorldRef, ChunkHolderManager> chunkManagerForOrNull) {
        return region -> {
            WorldRef world = worldForRegion.apply(region.id());
            if (world == null) return; // region died, or not yet claimed by any world
            ChunkHolderManager manager = chunkManagerForOrNull.apply(world);
            if (manager == null) return; // no chunk manager materialised for this world yet
            HolderManagerRegionData data = manager.regionData(region.id());
            List<TickingBlockEntityRef> tickers = data.snapshotBlockEntityTickers();
            for (TickingBlockEntityRef ticker : tickers) {
                try {
                    if (ticker.isRemoved()) {
                        data.removeBlockEntityTicker(ticker);
                        continue;
                    }
                    if (!ticker.shouldTick()) continue;
                    ticker.tick();
                } catch (Throwable t) {
                    ProbeRegistry.bump("block-entities.ticker.failure");
                    ViolationLogger.warn(
                            "BlockEntityTickRunner.standard",
                            "block entity ticker threw in region " + region.id() + " at " + ticker.pos() + ": "
                                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        };
    }
}
