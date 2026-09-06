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
package net.multiforge.runtime.chunk;

/**
 * The level of "loadedness" a chunk has reached, matching Vanilla's
 * {@code ChunkHolder.FullChunkStatus} ladder. Level numbers are
 * <em>inverted</em> — lower is more loaded — matching Vanilla's
 * DistanceManager where {@code level == 33} is BORDER, 32 is TICKING,
 * 31 is ENTITY_TICKING. Values >= 34 are unloaded.
 */
public enum ChunkLoadLevel {
    /** No load ticket keeps this chunk resident. Callers see nothing at this level. */
    INACCESSIBLE(34),

    /** Chunk exists and can be read, but does not tick block ticks or entities. */
    BORDER(33),

    /** Block ticks fire (fluid physics, block ticks, redstone). */
    TICKING(32),

    /** Entities tick (AI, physics, spawning). */
    ENTITY_TICKING(31);

    /** Vanilla-style distance number; lower = more loaded. */
    private final int distance;

    ChunkLoadLevel(int distance) {
        this.distance = distance;
    }

    public int distance() {
        return distance;
    }

    /** True if this level implies {@code other}'s stage as well (equal or more loaded). */
    public boolean isAtLeast(ChunkLoadLevel other) {
        return this.distance <= other.distance;
    }

    /**
     * @return the highest {@link ChunkLoadLevel} whose distance is
     *         &lt;= {@code d}, i.e. what a chunk with effective
     *         distance {@code d} actually reaches. Distances >= 34
     *         collapse to {@link #INACCESSIBLE}.
     */
    public static ChunkLoadLevel forDistance(int d) {
        if (d <= ENTITY_TICKING.distance) return ENTITY_TICKING;
        if (d <= TICKING.distance) return TICKING;
        if (d <= BORDER.distance) return BORDER;
        return INACCESSIBLE;
    }
}
