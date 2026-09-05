/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.ownership.OwnerToken;
import org.junit.jupiter.api.Test;

class RegionizedDataTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");

    @Test
    void freshRegionGetsFreshValueOnFirstTouch() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        AtomicInteger factoryCalls = new AtomicInteger();
        RegionizedData<List<String>> data = RegionizedData.of(
                () -> {
                    factoryCalls.incrementAndGet();
                    return new ArrayList<>();
                },
                (target, source) -> target.addAll(source));
        regionizer.addListener(data);

        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        OwnerToken.runAs(OwnerToken.forRegion(region.id().value()), () -> {
            List<String> slot = data.getOrCreate(region);
            slot.add("hello");
        });
        assertThat(factoryCalls.get()).isEqualTo(1);
        assertThat(data.peek(region)).containsExactly("hello");
    }

    @Test
    void twoRegionsHaveIndependentSlots() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);

        Region a = regionizer.addChunk(new ChunkPos(0, 0));
        Region b = regionizer.addChunk(new ChunkPos(100, 100));
        OwnerToken.runAs(
                OwnerToken.forRegion(a.id().value()), () -> data.getOrCreate(a).add("A"));
        OwnerToken.runAs(
                OwnerToken.forRegion(b.id().value()), () -> data.getOrCreate(b).add("B"));

        assertThat(data.peek(a)).containsExactly("A");
        assertThat(data.peek(b)).containsExactly("B");
        assertThat(data.size()).isEqualTo(2);
    }

    @Test
    void mergeFoldsSourceIntoTarget() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);

        // Two non-adjacent regions with distinct slot values, then bridge them with a chunk.
        // NEIGHBOURS is radius 1 (8-cell), so (0,0) and (3,0) are 2 sections apart → separate.
        // (2,0) is adjacent to (3,0) but not to (0,0). Adding (1,0) then (2,0) bridges via chain.
        Region left = regionizer.addChunk(new ChunkPos(0, 0));
        Region right = regionizer.addChunk(new ChunkPos(3, 0));
        assertThat(left).isNotSameAs(right);
        OwnerToken.runAs(OwnerToken.forRegion(left.id().value()), () -> data.getOrCreate(left)
                .add("L"));
        OwnerToken.runAs(OwnerToken.forRegion(right.id().value()), () -> data.getOrCreate(right)
                .add("R"));
        assertThat(data.size()).isEqualTo(2);

        // Bridge chunk (1,0) joins left; (2,0) joins right initially — the bridge that forces the
        // merge is a chunk simultaneously adjacent to both.
        regionizer.addChunk(new ChunkPos(1, 0)); // absorbs into left
        regionizer.addChunk(new ChunkPos(2, 0)); // adjacent to both — should merge left+right

        Region survivor = regionizer.regionAtChunk(0, 0);
        assertThat(survivor).isEqualTo(regionizer.regionAtChunk(3, 0));
        assertThat(data.peek(survivor)).contains("L", "R");
        assertThat(data.size()).isEqualTo(1);
    }

    @Test
    void splitPeelsFreshSlotViaSplitterCallback() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        // Splitter marks the child value distinctly so we can verify the callback ran.
        RegionizedData<List<String>> data =
                RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s), (sourceValue, child) -> {
                    List<String> childSlot = new ArrayList<>();
                    childSlot.add("split-child-of-" + child.id().value());
                    return childSlot;
                });
        regionizer.addListener(data);

        // Build a 3-in-a-row region A-B-C, seed its slot, then remove B to force split into A and C.
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        regionizer.addChunk(new ChunkPos(1, 0));
        regionizer.addChunk(new ChunkPos(2, 0));
        OwnerToken.runAs(OwnerToken.forRegion(region.id().value()), () -> data.getOrCreate(region)
                .add("original"));

        regionizer.removeChunk(new ChunkPos(1, 0));

        // Original region survives with one of the two remaining components; the other became a fresh region.
        Region survivor = regionizer.regionAtChunk(0, 0);
        Region peeled = regionizer.regionAtChunk(2, 0);
        assertThat(survivor).isNotEqualTo(peeled);
        // Survivor keeps its original slot (splitter never fires on the source itself).
        assertThat(data.peek(survivor)).contains("original");
        // Peeled child was populated by the splitter callback.
        assertThat(data.peek(peeled)).anyMatch(s -> s.startsWith("split-child-of-"));
    }

    @Test
    void offRegionAccessThrows() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);

        Region a = regionizer.addChunk(new ChunkPos(0, 0));
        Region b = regionizer.addChunk(new ChunkPos(100, 100));

        OwnerToken.runAs(OwnerToken.forRegion(b.id().value()), () -> assertThatThrownBy(() -> data.getOrCreate(a))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-owner"));
    }

    @Test
    void unknownDomainSkipsOwnerCheckToTolerateBootstrap() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        // No OwnerToken.runAs — plain thread, domain = UNKNOWN. Must not throw.
        data.getOrCreate(region).add("bootstrapped");
        assertThat(data.peek(region)).containsExactly("bootstrapped");
    }

    @Test
    void globalDomainMayReachAnyRegion() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);
        Region a = regionizer.addChunk(new ChunkPos(0, 0));
        Region b = regionizer.addChunk(new ChunkPos(100, 100));

        OwnerToken.runAs(OwnerToken.GLOBAL, () -> {
            data.getOrCreate(a).add("g-touch-a");
            data.getOrCreate(b).add("g-touch-b");
        });
        assertThat(data.peek(a)).containsExactly("g-touch-a");
        assertThat(data.peek(b)).containsExactly("g-touch-b");
    }

    @Test
    void regionDeathDropsSlot() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);
        Region region = regionizer.addChunk(new ChunkPos(0, 0));
        OwnerToken.runAs(OwnerToken.forRegion(region.id().value()), () -> data.getOrCreate(region)
                .add("gone"));

        assertThat(data.size()).isEqualTo(1);
        regionizer.removeChunk(new ChunkPos(0, 0));
        assertThat(data.size()).isZero();
    }
}
