/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.Test;

/**
 * /67 round-6 fork A HIGH finding regression coverage: {@link
 * MigratingEntityRef#addSettledListener} had a check-then-add race against {@link
 * MigratingEntityRef#fireSettled()}. {@code fireSettled()} is a one-shot
 * snapshot-and-clear of the listener list, not a repeating drain — a listener that landed
 * in the list <em>after</em> a concurrent terminal transition had already snapshotted-and-cleared
 * it would sit there forever, never fired, because the state machine never re-enters {@code
 * MIGRATING} to trigger another {@code fireSettled()} call for that ref.
 *
 * <p>The fix re-checks {@link MigratingEntityRef#migrationState()} after adding and, if the ref
 * has already settled, reclaims and fires the listener inline — with a {@code fired}-flag guard
 * so the inline path and a genuinely concurrent {@code fireSettled()} can't both run the same
 * listener.
 */
class MigratingEntityRefListenerRaceTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");
    private static final WorldRef NETHER = WorldRef.of("minecraft:the_nether");

    /**
     * N=1000 concurrent {@code addSettledListener()} calls race a single {@code
     * completeMigration()} call on the same ref. Before the fix, any listener whose {@code add}
     * landed just after {@code completeMigration()}'s {@code fireSettled()} snapshot-and-clear
     * would never fire, so this asserts both an aggregate exactly-1000 count and — the stronger
     * check — that every individual listener instance fired exactly once, never zero, never twice.
     */
    @Test
    void everyListenerFiresExactlyOnceUnderConcurrentAddVsComplete() throws InterruptedException {
        final int listenerCount = 1000;
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
        assertThat(ref.beginMigration()).isTrue();

        AtomicInteger totalFired = new AtomicInteger();
        AtomicInteger[] perListenerFireCounts = new AtomicInteger[listenerCount];
        for (int i = 0; i < listenerCount; i++) {
            perListenerFireCounts[i] = new AtomicInteger();
        }

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            for (int i = 0; i < listenerCount; i++) {
                int idx = i;
                pool.submit(() -> {
                    await(ready);
                    ref.addSettledListener(() -> {
                        totalFired.incrementAndGet();
                        perListenerFireCounts[idx].incrementAndGet();
                    });
                });
            }
            // The racer: wins the terminal CAS (RESIDENT) and runs fireSettled() concurrently
            // with the 1000 adds above -- exactly the race this test guards against.
            pool.submit(() -> {
                await(ready);
                ref.completeMigration(NETHER, new ChunkPos(5, 5));
            });

            ready.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("pool termination")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(ref.migrationState()).isEqualTo(MigrationState.RESIDENT);
        assertThat(totalFired.get()).isEqualTo(listenerCount);
        for (int i = 0; i < listenerCount; i++) {
            assertThat(perListenerFireCounts[i].get())
                    .as("listener %d fire count", i)
                    .isEqualTo(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
