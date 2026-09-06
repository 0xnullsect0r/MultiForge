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
