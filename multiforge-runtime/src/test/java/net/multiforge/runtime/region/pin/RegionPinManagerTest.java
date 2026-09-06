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
package net.multiforge.runtime.region.pin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionPinManagerTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");

    @Test
    void pinCoversRectangleInclusive() {
        RegionPin p = new RegionPin("base", OW, -2, -2, 2, 2);
        assertThat(p.contains(new ChunkPos(0, 0))).isTrue();
        assertThat(p.contains(new ChunkPos(-2, -2))).isTrue();
        assertThat(p.contains(new ChunkPos(2, 2))).isTrue();
        assertThat(p.contains(new ChunkPos(3, 0))).isFalse();
        assertThat(p.chunkCount()).isEqualTo(25);
    }

    @Test
    void pinNormalizesOpposingCorners() {
        RegionPin p = new RegionPin("base", OW, 10, 10, -10, -10);
        assertThat(p.fromChunkX()).isEqualTo(-10);
        assertThat(p.toChunkX()).isEqualTo(10);
    }

    @Test
    void addAndPinContainingFindsMatching(@TempDir Path tmp) throws IOException {
        RegionPinManager m = new RegionPinManager(tmp.resolve("pins.toml"));
        m.add(new RegionPin("base-north", OW, 0, 0, 5, 5));
        m.add(new RegionPin("base-south", OW, 20, 20, 25, 25));

        assertThat(m.pinContaining(OW, new ChunkPos(3, 3)).id()).isEqualTo("base-north");
        assertThat(m.pinContaining(OW, new ChunkPos(22, 22)).id()).isEqualTo("base-south");
        assertThat(m.pinContaining(OW, new ChunkPos(50, 50))).isNull();
    }

    @Test
    void addRejectsDuplicateId(@TempDir Path tmp) throws IOException {
        RegionPinManager m = new RegionPinManager(tmp.resolve("pins.toml"));
        m.add(new RegionPin("dup", OW, 0, 0, 1, 1));
        assertThatThrownBy(() -> m.add(new RegionPin("dup", OW, 5, 5, 6, 6))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void saveRoundTripsThroughDisk(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("pins.toml");
        RegionPinManager m = new RegionPinManager(file);
        m.add(new RegionPin("keep", OW, 0, 0, 3, 3));
        m.save();

        RegionPinManager loaded = RegionPinManager.load(file);
        assertThat(loaded.size()).isEqualTo(1);
        RegionPin round = loaded.byId("keep");
        assertThat(round).isNotNull();
        assertThat(round.chunkCount()).isEqualTo(16);
    }
}
