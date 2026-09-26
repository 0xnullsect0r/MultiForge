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
package net.multiforge.runtime.region.pin;

import java.util.Objects;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * An operator-created rectangle of chunks whose loaded chunks always tick
 * in one region: the regionizer merges every region holding a section of
 * the pin and never splits the pinned area apart, even when the loaded
 * chunks inside it are not adjacent. Used to keep a build that spans an
 * unloaded gap (two farms fed by one item line, a base and its chunk
 * loaders) on one thread.
 *
 * <p>A pin cannot isolate its area from loaded chunks next to it: regions
 * whose sections touch always merge, because that is what makes ticking
 * them in parallel safe (docs/design/barrier-tick-model.md).
 *
 * <p>Coordinates are inclusive on both ends. Bounds are normalized in
 * the compact ctor so callers can pass any two opposing corners.
 */
public record RegionPin(String id, WorldRef world, int fromChunkX, int fromChunkZ, int toChunkX, int toChunkZ) {

    public RegionPin {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(world, "world");
        if (id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("pin id must be 1-64 chars");
        }
        // Normalize: fromX ≤ toX, fromZ ≤ toZ.
        int minX = Math.min(fromChunkX, toChunkX);
        int maxX = Math.max(fromChunkX, toChunkX);
        int minZ = Math.min(fromChunkZ, toChunkZ);
        int maxZ = Math.max(fromChunkZ, toChunkZ);
        fromChunkX = minX;
        toChunkX = maxX;
        fromChunkZ = minZ;
        toChunkZ = maxZ;
    }

    public boolean contains(ChunkPos pos) {
        return pos.x() >= fromChunkX && pos.x() <= toChunkX && pos.z() >= fromChunkZ && pos.z() <= toChunkZ;
    }

    public int chunkCount() {
        return (toChunkX - fromChunkX + 1) * (toChunkZ - fromChunkZ + 1);
    }
}
