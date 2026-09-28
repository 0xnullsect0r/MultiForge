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
package net.multiforge.neoforge.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.LongUnaryOperator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import org.jetbrains.annotations.ApiStatus;

/**
 * Splits Vanilla's natural-spawn chunk count between regions.
 *
 * <p>Vanilla scales every mob cap by {@code DistanceManager.getNaturalSpawnChunkCount()}:
 * the number of chunks within Chebyshev distance 8 of a chunk holding a player
 * the chunk map tracks (its {@code playersPerChunk}, which leaves out the
 * players {@code ChunkMap.skipPlayer} skips), loaded or not: the union of a
 * 17×17 square around each such player chunk. A region's spawn state gets its
 * share of that count: each seed (a player chunk) belongs to the region owning
 * it, and each chunk of its square that no earlier seed claimed is credited to
 * that region. One level-wide set dedupes the squares, so overlapping squares
 * are counted once however the level is split into regions, and the credits
 * of all regions add up to Vanilla's count whenever every seed is in a region.
 *
 * <p>A region's cap is {@code limit × credit / 289}, rounded down per region,
 * so the region caps can add up to slightly less than the level cap; with one
 * player per region they are exactly Vanilla's.
 */
@ApiStatus.Internal
public final class SpawnChunkCredit {
    /** Vanilla's {@code DistanceManager.naturalSpawnChunkCounter} radius, in chunks. */
    public static final int RADIUS = 8;

    private SpawnChunkCredit() {}

    /** {@link #credit(LongSet, LongUnaryOperator, LongOpenHashSet, Long2IntOpenHashMap)} with {@code level}'s regions. */
    public static int credit(ServerLevel level, LongSet seeds, LongOpenHashSet seen, Long2IntOpenHashMap credits) {
        return credit(seeds, seed -> RegionizedTickCoordinator.regionIdAt(level, ChunkPos.getX(seed), ChunkPos.getZ(seed)), seen, credits);
    }

    /**
     * Credit the spawn chunks around {@code seeds} (packed chunk positions) to
     * the regions {@code regionOf} maps them to ({@code -1}: none; credited
     * under {@code -1}). Clears {@code seen} and {@code credits} first.
     *
     * @return the number of distinct spawn chunks, Vanilla's count
     */
    public static int credit(LongSet seeds, LongUnaryOperator regionOf, LongOpenHashSet seen, Long2IntOpenHashMap credits) {
        seen.clear();
        credits.clear();
        for (LongIterator it = seeds.iterator(); it.hasNext();) {
            long seed = it.nextLong();
            int cx = ChunkPos.getX(seed);
            int cz = ChunkPos.getZ(seed);
            int added = 0;
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (seen.add(ChunkPos.asLong(cx + dx, cz + dz))) added++;
                }
            }
            if (added > 0) credits.addTo(regionOf.applyAsLong(seed), added);
        }
        return seen.size();
    }
}
