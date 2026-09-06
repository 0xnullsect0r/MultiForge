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
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void offRegionAccessDoesNotThrowInProductionMode() {
        // /67 round-4 fix (B1): pre-fix code unconditionally threw
        // IllegalStateException on wrong-owner access, violating CLAUDE.md
        // rule 5 ("Never throw from a mod's code path"). With
        // -Dmultiforge.assert=on (dev/CI) it still throws; without it
        // (production default) the call degrades to a warn + probe bump
        // and returns the slot value anyway.
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);

        Region a = regionizer.addChunk(new ChunkPos(0, 0));
        Region b = regionizer.addChunk(new ChunkPos(100, 100));

        // Under the default (assertions off) the off-owner call must NOT
        // throw. If DomainAssertions.enabled() is true (JVM launched with
        // -Dmultiforge.assert=on) the call throws — test tolerates both.
        net.multiforge.runtime.diagnostics.ProbeRegistry.resetForTesting();
        boolean strictModeOn = net.multiforge.runtime.ownership.DomainAssertions.enabled();
        OwnerToken.runAs(OwnerToken.forRegion(b.id().value()), () -> {
            if (strictModeOn) {
                assertThatThrownBy(() -> data.getOrCreate(a))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("non-owner");
            } else {
                // Production degradation: no throw, probe bumped.
                data.getOrCreate(a).add("degraded-access");
                assertThat(net.multiforge.runtime.diagnostics.ProbeRegistry.get("RegionizedData.get:wrong-owner"))
                        .isGreaterThanOrEqualTo(1);
            }
        });
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
    void mergeSpinWaitsForSurvivingRegionToStopTicking() throws Exception {
        // Phase 1 task 1.1 quiescence contract: ThreadedRegionizer.mergeInto
        // must not fire onRegionsMerging while surviving is TICKING. We stage
        // the merge on the main thread while a worker thread holds surviving
        // in the TICKING state; the merge call must block (spin) until the
        // worker releases TICKING, then proceed.
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);

        // Build two adjacent regions the same way as mergeFoldsSourceIntoTarget:
        // left at (0,0), right at (3,0), then bridge with (1,0) then (2,0).
        Region left = regionizer.addChunk(new ChunkPos(0, 0));
        Region right = regionizer.addChunk(new ChunkPos(3, 0));
        OwnerToken.runAs(OwnerToken.forRegion(left.id().value()), () -> data.getOrCreate(left)
                .add("L"));
        OwnerToken.runAs(OwnerToken.forRegion(right.id().value()), () -> data.getOrCreate(right)
                .add("R"));
        regionizer.addChunk(new ChunkPos(1, 0));

        // Pin the anchor (the larger of left+bridged-1 vs. right) in TICKING on a worker thread.
        // pickAnchor picks the larger neighbour; after (1,0) is bridged into `left`, `left` has
        // sections {(0,0),(1,0)} while `right` has {(3,0)}. So the merge triggered by (2,0)
        // will pick `left` as surviving. We pin `left` in TICKING.
        Region survivingCandidate = regionizer.regionAtChunk(0, 0);
        assertThat(survivingCandidate.tryMarkTicking()).isTrue();
        assertThat(survivingCandidate.state()).isEqualTo(RegionState.TICKING);

        CountDownLatch mergeStarted = new CountDownLatch(1);
        AtomicBoolean mergeReturned = new AtomicBoolean(false);
        Thread merger = new Thread(
                () -> {
                    mergeStarted.countDown();
                    regionizer.addChunk(new ChunkPos(2, 0)); // triggers left+right merge
                    mergeReturned.set(true);
                },
                "test-merger");
        merger.start();
        assertThat(mergeStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // Give the merger thread time to reach the mergeInto spin. It must NOT complete
        // while surviving is TICKING — the spin holds it.
        Thread.sleep(100);
        assertThat(mergeReturned.get())
                .as("merge must spin-wait while surviving is TICKING")
                .isFalse();
        // Slot must not have been touched by the merger yet — surviving still owns its READY value.
        assertThat(data.peek(survivingCandidate)).containsExactly("L");

        // Release: mark not-ticking → merger's spin observes READY → tryMarkFolding succeeds → fold runs.
        survivingCandidate.markNotTicking();
        merger.join(5000);
        assertThat(mergeReturned.get()).isTrue();

        // Post-merge: surviving is back to READY (never left as FOLDING), and the fold happened.
        Region survivor = regionizer.regionAtChunk(0, 0);
        assertThat(survivor.state()).isEqualTo(RegionState.READY);
        assertThat(data.peek(survivor)).contains("L", "R");
    }

    @Test
    void mergeRunsInlineWhenSurvivingIsReady() {
        // Under normal (non-TICKING) conditions the merge fold happens
        // synchronously as before — the FOLDING quiesce is a no-op-fast-path
        // when surviving is READY (single successful CAS, no spin).
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);

        Region left = regionizer.addChunk(new ChunkPos(0, 0));
        Region right = regionizer.addChunk(new ChunkPos(3, 0));
        OwnerToken.runAs(OwnerToken.forRegion(left.id().value()), () -> data.getOrCreate(left)
                .add("L"));
        OwnerToken.runAs(OwnerToken.forRegion(right.id().value()), () -> data.getOrCreate(right)
                .add("R"));
        regionizer.addChunk(new ChunkPos(1, 0));

        // Both left and right are READY. Trigger the merge; it must complete synchronously.
        long start = System.nanoTime();
        regionizer.addChunk(new ChunkPos(2, 0));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        Region survivor = regionizer.regionAtChunk(0, 0);
        assertThat(survivor.state()).isEqualTo(RegionState.READY);
        assertThat(data.peek(survivor)).contains("L", "R");
        // Sanity check that the "no wait" path completed fast (well under any realistic tick).
        assertThat(elapsedMs).isLessThan(500L);
    }

    @Test
    void tryMarkTickingRefusesFoldingState() {
        // Direct unit check on the new state transitions: a region in FOLDING
        // must not be markable as TICKING (so a worker cannot start a tick
        // while the regionizer is folding merge listener state into it).
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        Region r = regionizer.addChunk(new ChunkPos(0, 0));
        assertThat(r.state()).isEqualTo(RegionState.READY);

        assertThat(r.tryMarkFolding()).isTrue();
        assertThat(r.state()).isEqualTo(RegionState.FOLDING);
        assertThat(r.tryMarkTicking()).as("tryMarkTicking must refuse FOLDING").isFalse();
        assertThat(r.state()).isEqualTo(RegionState.FOLDING);

        assertThat(r.markReadyFromFolding()).isTrue();
        assertThat(r.state()).isEqualTo(RegionState.READY);
        // Now that we're back to READY, tryMarkTicking works normally.
        assertThat(r.tryMarkTicking()).isTrue();
        assertThat(r.state()).isEqualTo(RegionState.TICKING);
        r.markNotTicking();
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
