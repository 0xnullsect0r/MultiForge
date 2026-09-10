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
package net.multiforge.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import org.junit.jupiter.api.Test;

class DebugHudStateTest {

    private static DebugHudState populated() {
        DebugHudState state = new DebugHudState();
        state.apply(new DebugPayload.Hello(2, 20, "test-build"));
        state.apply(new DebugPayload.RegionSnapshot(1L, List.of(new DebugPayload.RegionStat(7, 2, 1.0, 2.0, 3))));
        state.apply(
                new DebugPayload.HeatmapUpdate("minecraft:overworld", List.of(new DebugPayload.ChunkHeat(0, 0, 5f))));
        state.apply(new DebugPayload.PinList(List.of(new DebugPayload.PinBox("p", "minecraft:overworld", 0, 0, 1, 1))));
        state.apply(new DebugPayload.ViolationEvent(1L, "mod", "site", "detail"));
        state.apply(new DebugPayload.OwnershipUpdate(
                "minecraft:overworld", 2, List.of(new DebugPayload.SectionOwner(0, 0, 7L))));
        return state;
    }

    @Test
    void clearWipesEveryServerSuppliedField() {
        DebugHudState state = populated();
        state.markProtocolUnsupported(99);

        state.clear();

        assertThat(state.hello()).isNull();
        assertThat(state.latestSnapshot()).isNull();
        assertThat(state.latestHeatmap()).isNull();
        assertThat(state.pins()).isEmpty();
        assertThat(state.recentViolations()).isEmpty();
        assertThat(state.f3Lines()).isEmpty();
        assertThat(state.hasOwnershipFor("minecraft:overworld")).isFalse();
        assertThat(state.regionIdAtChunk(0, 0)).isNull();
        assertThat(state.protocolUnsupported()).isFalse();
        assertThat(state.serverProtocol()).isZero();
    }

    @Test
    void clearPreservesTheUsersOverlayPreference() {
        DebugHudState state = populated();
        assertThat(state.toggleOverlays()).isFalse();
        assertThat(state.overlaysEnabled()).isFalse();

        state.clear();

        // The F6 toggle is a user preference, not server state — a
        // disconnect must not silently switch the overlays back on.
        assertThat(state.overlaysEnabled()).isFalse();
    }

    @Test
    void toggleOverlaysFlipsAndReportsNewValue() {
        DebugHudState state = new DebugHudState();
        assertThat(state.overlaysEnabled()).isTrue();
        assertThat(state.toggleOverlays()).isFalse();
        assertThat(state.toggleOverlays()).isTrue();
        assertThat(state.overlaysEnabled()).isTrue();
    }

    @Test
    void ownershipResolvesEveryChunkInsideASection() {
        DebugHudState state = new DebugHudState();
        // shift 2 → 4x4 chunks per section, origin (0,0) and (4,0).
        state.apply(new DebugPayload.OwnershipUpdate(
                "minecraft:overworld",
                2,
                List.of(new DebugPayload.SectionOwner(0, 0, 11L), new DebugPayload.SectionOwner(4, 0, 22L))));

        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                assertThat(state.regionIdAtChunk(x, z)).as("chunk %d,%d", x, z).isEqualTo(11L);
            }
        }
        assertThat(state.regionIdAtChunk(4, 0)).isEqualTo(22L);
        assertThat(state.regionIdAtChunk(7, 3)).isEqualTo(22L);
    }

    @Test
    void ownershipFloorsNegativeChunksToTheirSectionOrigin() {
        DebugHudState state = new DebugHudState();
        // Arithmetic shift, matching the server's SectionPos.ofChunk:
        // chunk -1 belongs to section origin -4 at shift 2.
        state.apply(new DebugPayload.OwnershipUpdate(
                "minecraft:overworld", 2, List.of(new DebugPayload.SectionOwner(-4, -4, 33L))));

        assertThat(state.regionIdAtChunk(-1, -1)).isEqualTo(33L);
        assertThat(state.regionIdAtChunk(-4, -4)).isEqualTo(33L);
        // One past the section edge is a different, unknown section.
        assertThat(state.regionIdAtChunk(-5, -4)).isNull();
    }

    @Test
    void ownershipIsScopedToItsWorld() {
        DebugHudState state = new DebugHudState();
        state.apply(new DebugPayload.OwnershipUpdate(
                "minecraft:the_nether", 0, List.of(new DebugPayload.SectionOwner(0, 0, 1L))));

        assertThat(state.hasOwnershipFor("minecraft:the_nether")).isTrue();
        assertThat(state.hasOwnershipFor("minecraft:overworld")).isFalse();
    }

    @Test
    void ownershipUpdateReplacesRatherThanMerges() {
        DebugHudState state = new DebugHudState();
        state.apply(new DebugPayload.OwnershipUpdate(
                "minecraft:overworld", 0, List.of(new DebugPayload.SectionOwner(0, 0, 1L))));
        state.apply(new DebugPayload.OwnershipUpdate(
                "minecraft:overworld", 0, List.of(new DebugPayload.SectionOwner(5, 5, 2L))));

        assertThat(state.regionIdAtChunk(0, 0)).isNull();
        assertThat(state.regionIdAtChunk(5, 5)).isEqualTo(2L);
    }

    @Test
    void noOwnershipFrameMeansNoAnswer() {
        DebugHudState state = new DebugHudState();
        assertThat(state.hasOwnershipFor("minecraft:overworld")).isFalse();
        assertThat(state.regionIdAtChunk(0, 0)).isNull();
    }
}
