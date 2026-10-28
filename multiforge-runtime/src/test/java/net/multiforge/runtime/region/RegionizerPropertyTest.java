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
        check(ops, shift, 0, 0, -12, 12);
    }

    /**
     * The sizes servers run (shift 3 = 8 chunks, the configurable minimum, to 8),
     * anywhere up to the world border. Ops pick a section in a 13x13 window
     * and a chunk inside it, so merges and splits still collide at large sizes.
     */
    @Provide
    Arbitrary<Op> sectionOps() {
        return Combinators.combine(
                        Arbitraries.of(true, true, false),
                        Arbitraries.integers().between(-6 * 256, 6 * 256 + 255),
                        Arbitraries.integers().between(-6 * 256, 6 * 256 + 255))
                .as(Op::new);
    }

    @Property(tries = 200)
    void productionSizesAnywhereInTheWorld(
            @ForAll @Size(max = 150) List<@net.jqwik.api.From("sectionOps") Op> ops,
            @ForAll @IntRange(min = 3, max = 8) int shift,
            @ForAll @IntRange(min = -1_800_000, max = 1_800_000) int baseX,
            @ForAll @IntRange(min = -1_800_000, max = 1_800_000) int baseZ) {
        // Op coordinates are in 1/256-section units; scale them to this shift.
        List<Op> scaled = ops.stream()
                .map(o -> new Op(o.add(), scale(o.x(), shift), scale(o.z(), shift)))
                .toList();
        check(scaled, shift, baseX, baseZ, -6 << shift, (7 << shift) - 1);
    }

    private static int scale(int units, int shift) {
        int sectionIndex = Math.floorDiv(units, 256);
        int inside = Math.floorMod(units, 256) >> (8 - shift);
        return (sectionIndex << shift) + inside;
    }

    /**
     * Apply {@code ops} (offset by the base, aligned to a section) and compare the
     * regions with the 8-connected components of occupied sections; {@code
     * min..max} is the op window in chunks relative to the base.
     */
    private static void check(List<Op> ops, int shift, int baseX, int baseZ, int min, int max) {
        int bx = (baseX >> shift) << shift;
        int bz = (baseZ >> shift) << shift;
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, shift);
        Set<ChunkPos> loaded = new HashSet<>();
        for (Op op : ops) {
            ChunkPos pos = new ChunkPos(bx + op.x(), bz + op.z());
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
        // No region survives for a section with nothing loaded (one chunk per section).
        int step = Math.max(1, (max - min + 1) / 25);
        for (int x = min; x <= max; x += step) {
            for (int z = min; z <= max; z += step) {
                if (!occupied.contains(section((bx + x) >> shift, (bz + z) >> shift))) {
                    assertThat(regionizer.regionAtChunk(bx + x, bz + z)).isNull();
                }
            }
        }
    }

    private static long section(int sx, int sz) {
        return ((long) sx << 32) | (sz & 0xFFFFFFFFL);
    }
}
