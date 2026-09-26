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
package net.multiforge.runtime.chunk;

import java.util.Objects;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;

/**
 * A loaded chunk as the regionized runtime sees it: its position and the
 * region that owns it. Created when Vanilla loads the chunk ({@code
 * ChunkEvent.Load}) and dropped when it unloads; Vanilla's own {@code
 * ChunkHolder} keeps everything else (load level, futures, players).
 */
public final class NewChunkHolder {

    private final WorldRef world;
    private final ChunkPos position;
    private volatile RegionId owningRegion;

    NewChunkHolder(WorldRef world, ChunkPos position) {
        this.world = Objects.requireNonNull(world, "world");
        this.position = Objects.requireNonNull(position, "position");
    }

    public WorldRef world() {
        return world;
    }

    public ChunkPos position() {
        return position;
    }

    /** The region owning this chunk, or {@code null} before the first assignment. */
    public RegionId owningRegion() {
        return owningRegion;
    }

    /** @return {@code true} if the owner changed */
    boolean setOwningRegion(RegionId region) {
        RegionId prev = this.owningRegion;
        this.owningRegion = region;
        return !Objects.equals(prev, region);
    }

    @Override
    public String toString() {
        return "NewChunkHolder[" + world.dimensionId() + " " + position + " owner=" + owningRegion + "]";
    }
}
