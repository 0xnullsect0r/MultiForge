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
package net.multiforge.runtime.region;

import java.util.List;
import net.multiforge.api.world.ChunkPos;

/**
 * Resolves the chunks a given region currently owns. Backs {@link
 * Region#ownedChunkSnapshot()} (docs/design/m13-b3-region-tick.md
 * §4.3).
 *
 * <p>This is a plain functional interface — not a direct reference to
 * {@code net.multiforge.runtime.chunk.ChunkHolderManager} — precisely
 * so the {@code region} package stays free of any dependency on the
 * {@code chunk} package. {@code chunk} already depends on {@code
 * region} (a {@link Region}, a {@link RegionId}, {@link
 * RegionListener}); the reverse edge would make the two packages
 * mutually dependent. In production, {@code
 * net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost} wires
 * one instance per world at region-creation time (see {@link
 * Region#withChunkSource}), backed by {@code
 * ChunkHolderManager.holdersOwnedBy(RegionId)}.
 */
@FunctionalInterface
public interface RegionChunkSource {

    /** Snapshot of the chunks {@code region} currently owns. Never {@code null}. */
    List<ChunkPos> ownedChunkSnapshot(RegionId region);
}
