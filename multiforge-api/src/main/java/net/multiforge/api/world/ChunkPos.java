/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.world;

/**
 * Chunk-space coordinate pair (one chunk = 16 blocks). Matches
 * Vanilla's {@code ChunkPos} in meaning; MultiForge exposes its own
 * record so the API has no Minecraft dependency.
 */
public record ChunkPos(int x, int z) {

    public static ChunkPos ofBlock(int blockX, int blockZ) {
        return new ChunkPos(blockX >> 4, blockZ >> 4);
    }
}
