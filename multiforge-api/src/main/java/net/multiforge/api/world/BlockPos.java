/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.world;

/** Block-space coordinate triple. */
public record BlockPos(int x, int y, int z) {

    public ChunkPos toChunkPos() {
        return new ChunkPos(x >> 4, z >> 4);
    }
}
