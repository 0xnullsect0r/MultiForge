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
