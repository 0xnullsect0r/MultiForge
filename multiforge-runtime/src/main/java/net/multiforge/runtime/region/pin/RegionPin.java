/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region.pin;

import java.util.Objects;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * An operator-created rectangle of chunks that must live in one
 * dedicated region — never merged with adjacent regions, never split.
 * Used to keep two nearby bases isolated so one lag spike doesn't
 * bleed into the other.
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
