/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.ownership.OwnerToken;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the concrete bugs the /67 review found in this
 * session's session-1 runtime code — one test per finding, each
 * exercising the specific failure path the /67 report cited.
 */
class RegionizerReviewFixesTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");

    // /67 finding #1: split-created regions must enter the scheduler via onRegionCreated.
    @Test
    void splitCreatedRegionsGetScheduled() throws Exception {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        try (TickRegionScheduler scheduler = new TickRegionScheduler(1, r -> {}, queue, 32)) {
            regionizer.addListener(scheduler);
            regionizer.addListener(queue);

            // Build A-B-C (single region), then remove B → C peels off as fresh region.
            regionizer.addChunk(new ChunkPos(0, 0));
            regionizer.addChunk(new ChunkPos(1, 0));
            regionizer.addChunk(new ChunkPos(2, 0));
            Region beforeSplit = regionizer.regionAtChunk(0, 0);
            scheduler.register(beforeSplit); // simulate the touchChunk registration for the original

            regionizer.removeChunk(new ChunkPos(1, 0));

            Region peeled = regionizer.regionAtChunk(2, 0);
            assertThat(peeled).isNotEqualTo(beforeSplit);
            // The critical assertion: the peeled region has an MSPT record because it was
            // auto-registered. Before the fix onRegionCreated was default-no-op → mspt returned null.
            assertThat(scheduler.mspt(peeled)).isNotNull();
        }
    }

    // /67 finding #5: queueChunkTask task must not be lost when a concurrent merge/death happens.
    @Test
    void queueChunkTaskAgainstDeadRegionOrphansItsTask() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedTaskQueue queue = RegionizedTaskQueue.of(regionizer);
        regionizer.addListener(queue);

        // Load a chunk; capture its region; then kill the region by removing its last chunk.
        regionizer.addChunk(new ChunkPos(50, 50));
        Region owner = regionizer.regionAtChunk(50, 50);
        assertThat(owner).isNotNull();

        // Manually kill the region: this simulates the removeChunk → onRegionDied cascade.
        regionizer.removeChunk(new ChunkPos(50, 50));
        assertThat(owner.state()).isEqualTo(RegionState.DEAD);

        // Now queue a task against the (now unloaded) chunk — pretend we got the reference
        // just as death happened. queueChunkTask sees no owner → orphan queue.
        AtomicInteger ran = new AtomicInteger();
        queue.queueChunkTask(WORLD, 50, 50, ran::incrementAndGet);
        assertThat(queue.orphanedSize()).isEqualTo(1);

        // Reload the chunk; reroute() should home the orphaned task.
        Region reborn = regionizer.addChunk(new ChunkPos(50, 50));
        queue.reroute();
        assertThat(queue.inboxSize(reborn)).isEqualTo(1);
        queue.drain(reborn, 10);
        assertThat(ran.get()).isEqualTo(1);
    }

    // /67 finding #6: merger.accept must not run mid-tick against a TICKING surviving region.
    @Test
    void mergeDefersFoldWhenSurvivingRegionIsTicking() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(WORLD, 0);
        RegionizedData<List<String>> data = RegionizedData.of(ArrayList::new, (t, s) -> t.addAll(s));
        regionizer.addListener(data);

        // Build two non-adjacent regions with slot values.
        Region left = regionizer.addChunk(new ChunkPos(0, 0));
        Region right = regionizer.addChunk(new ChunkPos(3, 0));
        OwnerToken.runAs(OwnerToken.forRegion(left.id().value()), () -> data.getOrCreate(left)
                .add("L"));
        OwnerToken.runAs(OwnerToken.forRegion(right.id().value()), () -> data.getOrCreate(right)
                .add("R"));

        // Simulate: left is currently TICKING when the merge fires.
        assertThat(left.tryMarkTicking()).isTrue();

        // Bridge chunks to force merge into left (larger anchor).
        regionizer.addChunk(new ChunkPos(1, 0));
        regionizer.addChunk(new ChunkPos(2, 0));

        // The merger should have DEFERRED because left was TICKING —
        // left's postTickActionCount is now > 0 and right's slot value
        // has NOT been folded into left's slot yet.
        assertThat(left.postTickActionCount()).isEqualTo(1);
        assertThat(data.peek(left)).containsExactly("L"); // fold not yet applied

        // Complete the "tick" — the deferred action runs.
        left.markNotTicking();
        left.runPostTickActions();

        assertThat(left.postTickActionCount()).isZero();
        assertThat(data.peek(left)).contains("L", "R");
    }
}
