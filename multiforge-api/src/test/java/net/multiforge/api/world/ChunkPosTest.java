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
package net.multiforge.api.world;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChunkPosTest {

    @Test
    void blockToChunkArithmetic() {
        assertThat(ChunkPos.ofBlock(0, 0)).isEqualTo(new ChunkPos(0, 0));
        assertThat(ChunkPos.ofBlock(15, 15)).isEqualTo(new ChunkPos(0, 0));
        assertThat(ChunkPos.ofBlock(16, 16)).isEqualTo(new ChunkPos(1, 1));
        assertThat(ChunkPos.ofBlock(-1, -1)).isEqualTo(new ChunkPos(-1, -1));
        assertThat(ChunkPos.ofBlock(-16, -16)).isEqualTo(new ChunkPos(-1, -1));
        assertThat(ChunkPos.ofBlock(-17, -17)).isEqualTo(new ChunkPos(-2, -2));
    }

    @Test
    void blockPosToChunk() {
        assertThat(new BlockPos(35, 64, -17).toChunkPos()).isEqualTo(new ChunkPos(2, -2));
    }
}
