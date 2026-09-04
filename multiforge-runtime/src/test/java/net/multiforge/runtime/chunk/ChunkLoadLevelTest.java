/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChunkLoadLevelTest {

    @Test
    void forDistanceMapsToLadder() {
        assertThat(ChunkLoadLevel.forDistance(30)).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
        assertThat(ChunkLoadLevel.forDistance(31)).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
        assertThat(ChunkLoadLevel.forDistance(32)).isEqualTo(ChunkLoadLevel.TICKING);
        assertThat(ChunkLoadLevel.forDistance(33)).isEqualTo(ChunkLoadLevel.BORDER);
        assertThat(ChunkLoadLevel.forDistance(34)).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
        assertThat(ChunkLoadLevel.forDistance(50)).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
    }

    @Test
    void isAtLeastMonotone() {
        assertThat(ChunkLoadLevel.ENTITY_TICKING.isAtLeast(ChunkLoadLevel.TICKING))
                .isTrue();
        assertThat(ChunkLoadLevel.TICKING.isAtLeast(ChunkLoadLevel.BORDER)).isTrue();
        assertThat(ChunkLoadLevel.BORDER.isAtLeast(ChunkLoadLevel.INACCESSIBLE)).isTrue();
        assertThat(ChunkLoadLevel.BORDER.isAtLeast(ChunkLoadLevel.TICKING)).isFalse();
    }
}
