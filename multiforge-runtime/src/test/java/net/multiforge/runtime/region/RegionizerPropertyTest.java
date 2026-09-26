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
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Property tests for {@link ThreadedRegionizer}: after any sequence of chunk
 * loads and unloads, its regions are exactly the 8-connected components of
 * the sections that still hold a loaded chunk — compared against a direct
 * model of the loaded set.
 */
class RegionizerPropertyTest {
    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    record Op(boolean add, int x, int z) {}

    @Provide
    Arbitrary<Op> ops() {
        // A small window so loads, unloads, merges and splits collide often.
        return Combinators.combine(
                        Arbitraries.of(true, true, false),
                        Arbitraries.integers().between(-12, 12),
                        Arbitraries.integers().between(-12, 12))
                .as(Op::new);
    }

    @Property(tries = 300)
    void regionsAreTheConnectedComponentsOfOccupiedSections(
            @ForAll @Size(max = 120) List<@net.jqwik.api.From("ops") Op> ops,
            @ForAll @IntRange(min = 0, max = 2) int shift) {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, shift);
        Set<ChunkPos> loaded = new HashSet<>();
        for (Op op : ops) {
            ChunkPos pos = new ChunkPos(op.x(), op.z());
            if (op.add()) {
                regionizer.addChunk(pos);
                loaded.add(pos);
            } else {
                regionizer.removeChunk(pos);
                loaded.remove(pos);
            }
        }

        // Model: occupied sections and their 8-connected components.
        Set<Long> occupied = new HashSet<>();
        for (ChunkPos p : loaded) occupied.add(section(p.x() >> shift, p.z() >> shift));
        Map<Long, Integer> component = new HashMap<>();
        int components = 0;
        for (long start : occupied) {
            if (component.containsKey(start)) continue;
            int id = components++;
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(start));
            component.put(start, id);
            while (!queue.isEmpty()) {
                long s = queue.poll();
                int sx = (int) (s >> 32);
                int sz = (int) s;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long n = section(sx + dx, sz + dz);
                        if (occupied.contains(n) && component.putIfAbsent(n, id) == null) queue.add(n);
                    }
                }
            }
        }

        assertThat(regionizer.regions()).hasSize(components);
        for (Region r : regionizer.regions()) assertThat(r.state()).isNotEqualTo(RegionState.DEAD);
        // Every loaded chunk has a region; same region iff same component.
        Map<Integer, Region> regionOfComponent = new HashMap<>();
        for (ChunkPos p : loaded) {
            Region r = regionizer.regionAtChunk(p);
            assertThat(r).as("loaded chunk %s has a region", p).isNotNull();
            assertThat(regionizer.isChunkLoaded(p)).isTrue();
            int c = component.get(section(p.x() >> shift, p.z() >> shift));
            Region prior = regionOfComponent.putIfAbsent(c, r);
            if (prior != null) assertThat(r).isSameAs(prior);
        }
        assertThat(new HashSet<>(regionOfComponent.values())).hasSize(components);
        // No region survives for a section with nothing loaded.
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                if (!occupied.contains(section(x >> shift, z >> shift))) {
                    assertThat(regionizer.regionAtChunk(x, z)).isNull();
                }
            }
        }
    }

    private static long section(int sx, int sz) {
        return ((long) sx << 32) | (sz & 0xFFFFFFFFL);
    }
}
