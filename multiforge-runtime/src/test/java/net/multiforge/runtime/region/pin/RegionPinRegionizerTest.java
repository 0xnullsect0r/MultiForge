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

import java.nio.file.Path;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionPinRegionizerTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");
    private static final WorldRef NETHER = WorldRef.of("minecraft:the_nether");

    // shift 0: one section per chunk, so (0,0) and (5,0) are far apart.
    private static ThreadedRegionizer regionizer(RegionPinManager pins) {
        ThreadedRegionizer r = new ThreadedRegionizer(WORLD, 0);
        r.setPins(pins::all);
        return r;
    }

    @Test
    void unpinnedDistantChunksAreSeparateRegions(@TempDir Path tmp) {
        ThreadedRegionizer r = regionizer(new RegionPinManager(tmp.resolve("pins.json")));
        r.addChunk(new ChunkPos(0, 0));
        r.addChunk(new ChunkPos(5, 0));
        assertThat(r.regions()).hasSize(2);
    }

    @Test
    void pinnedDistantChunksShareOneRegion(@TempDir Path tmp) {
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.json"));
        pins.add(new RegionPin("farm", WORLD, 0, 0, 5, 0));
        ThreadedRegionizer r = regionizer(pins);
        r.addChunk(new ChunkPos(0, 0));
        r.addChunk(new ChunkPos(5, 0));
        r.addChunk(new ChunkPos(20, 0));
        assertThat(r.regions()).hasSize(2);
        assertThat(r.regionAtChunk(0, 0)).isSameAs(r.regionAtChunk(5, 0));
        assertThat(r.regionAtChunk(20, 0)).isNotSameAs(r.regionAtChunk(0, 0));
    }

    @Test
    void removingABridgeChunkDoesNotSplitAPin(@TempDir Path tmp) {
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.json"));
        pins.add(new RegionPin("farm", WORLD, 0, 0, 4, 0));
        ThreadedRegionizer r = regionizer(pins);
        for (int x = 0; x <= 4; x++) r.addChunk(new ChunkPos(x, 0));
        r.removeChunk(new ChunkPos(2, 0));
        assertThat(r.regions()).hasSize(1);
    }

    @Test
    void addingAPinMergesAndRemovingItSplits(@TempDir Path tmp) {
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.json"));
        ThreadedRegionizer r = regionizer(pins);
        pins.addChangeListener(r::refreshPins);
        r.addChunk(new ChunkPos(0, 0));
        r.addChunk(new ChunkPos(5, 0));
        assertThat(r.regions()).hasSize(2);

        pins.add(new RegionPin("farm", WORLD, 0, 0, 5, 0));
        assertThat(r.regions()).hasSize(1);

        pins.remove("farm");
        assertThat(r.regions()).hasSize(2);
        assertThat(r.regionAtChunk(0, 0)).isNotSameAs(r.regionAtChunk(5, 0));
    }

    @Test
    void pinsOfOtherWorldsAreIgnored(@TempDir Path tmp) {
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.json"));
        pins.add(new RegionPin("farm", NETHER, 0, 0, 5, 0));
        ThreadedRegionizer r = regionizer(pins);
        r.addChunk(new ChunkPos(0, 0));
        r.addChunk(new ChunkPos(5, 0));
        assertThat(r.regions()).hasSize(2);
    }

    @Test
    void hugePinOnlyWalksLoadedSections(@TempDir Path tmp) {
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.json"));
        pins.add(new RegionPin("world", WORLD, -1_000_000, -1_000_000, 1_000_000, 1_000_000));
        ThreadedRegionizer r = regionizer(pins);
        r.addChunk(new ChunkPos(0, 0));
        r.addChunk(new ChunkPos(900_000, -900_000));
        r.removeChunk(new ChunkPos(0, 0));
        r.addChunk(new ChunkPos(-5, 7));
        assertThat(r.regions()).hasSize(1);
    }
}
