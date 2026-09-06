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
package net.multiforge.runtime.chunk.regression;

import static org.assertj.core.api.Assertions.assertThat;

import net.multiforge.runtime.chunk.ChunkLoadLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 cohorts 6.10 + 6.12 regression — {@code Visibility.fromFullChunkStatus}
 * and {@code PersistentEntitySectionManager.updateChunkStatus}.
 *
 * <p>The Vanilla contract is:
 * <ul>
 *   <li>{@code FullChunkStatus.INACCESSIBLE} → {@code Visibility.HIDDEN}</li>
 *   <li>{@code FullChunkStatus.FULL} → {@code Visibility.TRACKED}</li>
 *   <li>{@code FullChunkStatus.BLOCK_TICKING} → {@code Visibility.TRACKED}</li>
 *   <li>{@code FullChunkStatus.ENTITY_TICKING} → {@code Visibility.TICKING}</li>
 * </ul>
 *
 * <p>MultiForge preserves this mapping through its
 * {@link ChunkLoadLevel} ladder, which maps 1-1 to Vanilla's
 * {@code FullChunkStatus}. Entity ticking (Visibility.TICKING) requires
 * MultiForge level {@link ChunkLoadLevel#ENTITY_TICKING}; tracking
 * (Visibility.TRACKED) is any level {@code isAtLeast(BORDER)} but not
 * ENTITY_TICKING; anything below BORDER is hidden.
 *
 * <p>This test pins the ladder-parity invariant that
 * {@code PersistentEntitySectionManager.updateChunkStatus} depends on
 * so that entity gating (start/stop ticking, start/stop tracking) fires
 * at the same status boundaries under MultiForge as under Vanilla.
 *
 * <p>The full end-to-end assertion (feeding a real
 * {@code PersistentEntitySectionManager} a status transition and
 * observing entity tick lifecycle callbacks) requires Minecraft
 * classes on the classpath. The MC-free runtime module cannot host
 * that fixture — it is marked {@code @Tag("integration")} and lives
 * here as documentation of the contract MultiForge must not drift from;
 * the full integration is covered by the bench module's
 * gameTestServer fixtures.
 */
@Tag("integration")
class Cohort612EntityVisibilityRoutingTest {

    @Test
    void multiForgeLadderMatchesVanillaVisibilityBoundaries() {
        // The four MultiForge levels map to the four Vanilla
        // FullChunkStatus / Visibility values in this exact order —
        // the entity manager's gating is level-comparison based, so
        // any drift here silently breaks entity ticking on chunks that
        // Vanilla would have ticked.
        assertThat(ChunkLoadLevel.INACCESSIBLE.isAtLeast(ChunkLoadLevel.BORDER))
                .as("INACCESSIBLE must map to Visibility.HIDDEN — never accessible")
                .isFalse();
        assertThat(ChunkLoadLevel.BORDER.isAtLeast(ChunkLoadLevel.BORDER))
                .as("BORDER must map to Visibility.TRACKED — accessible but not ticking")
                .isTrue();
        assertThat(ChunkLoadLevel.BORDER.isAtLeast(ChunkLoadLevel.ENTITY_TICKING))
                .as("BORDER must NOT map to Visibility.TICKING")
                .isFalse();
        assertThat(ChunkLoadLevel.TICKING.isAtLeast(ChunkLoadLevel.BORDER))
                .as("TICKING must map to Visibility.TRACKED at least — accessible")
                .isTrue();
        assertThat(ChunkLoadLevel.TICKING.isAtLeast(ChunkLoadLevel.ENTITY_TICKING))
                .as("TICKING (block ticking) must NOT map to Visibility.TICKING (entity ticking)")
                .isFalse();
        assertThat(ChunkLoadLevel.ENTITY_TICKING.isAtLeast(ChunkLoadLevel.ENTITY_TICKING))
                .as("ENTITY_TICKING must map to Visibility.TICKING")
                .isTrue();
    }
}
