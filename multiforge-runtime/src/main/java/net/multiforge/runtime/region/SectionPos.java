/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

/**
 * Coordinate of a region <em>section</em> — a coarse tile of
 * {@code 2^sectionChunkShift} chunks per side. Folia's default of 16
 * chunks per section (sectionChunkShift = 4) collapses per-chunk work
 * into per-section work, cutting the regionizer state machine cost by
 * ~256×.
 */
public record SectionPos(int x, int z) {

    public static SectionPos ofChunk(int chunkX, int chunkZ, int sectionChunkShift) {
        return new SectionPos(chunkX >> sectionChunkShift, chunkZ >> sectionChunkShift);
    }
}
