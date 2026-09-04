/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
