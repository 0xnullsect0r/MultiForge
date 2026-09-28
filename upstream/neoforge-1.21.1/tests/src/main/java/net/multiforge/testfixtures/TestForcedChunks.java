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
package net.multiforge.testfixtures;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Force-loads a chunk for a test without taking away a force someone else
 * holds. {@code ServerLevel}'s forced chunks are a set, not a count: the
 * GameTest runner forces every test structure's chunks, and neighbouring tests
 * of a batch share chunks, so a test that un-forced a chunk it found already
 * forced stopped another test's entities from ticking (a batch neighbour's 30
 * pigs never crammed).
 */
final class TestForcedChunks {
    private TestForcedChunks() {}

    /**
     * Forces {@code chunk} and returns what releases it: un-forcing it only when
     * this call added the force.
     */
    static Runnable force(ServerLevel level, ChunkPos chunk) {
        boolean added = level.setChunkForced(chunk.x, chunk.z, true);
        return added ? () -> level.setChunkForced(chunk.x, chunk.z, false) : () -> {};
    }
}
