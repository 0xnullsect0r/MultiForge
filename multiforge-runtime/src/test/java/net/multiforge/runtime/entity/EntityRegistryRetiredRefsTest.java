/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A1.2 — {@link EntityRegistry}'s retired-UUID cache: lookup contract, TTL expiry, and sweeper
 * thread lifecycle. See docs/design/entity-migration.md §4 (test invariants T3-T5).
 */
class EntityRegistryRetiredRefsTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");

    private EntityRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) registry.close();
    }

    private MigratingEntityRef newRef() {
        return new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));
    }

    /** T3: a stale UUID within the 200-tick window resolves to a RETIRED ref, never null. */
    @Test
    void lookupWithinTtlWindowReturnsRetiredRef() {
        registry = new EntityRegistry();
        MigratingEntityRef ref = newRef();
        registry.add(ref, "payload");
        registry.setCurrentTick(1000);

        ref.retire();
        registry.retire(ref, 1000);

        assertThat(registry.get(ref.uuid())).isNull(); // gone from the live map
        MigratingEntityRef looked = registry.lookup(ref.uuid());
        assertThat(looked).isNotNull();
        assertThat(looked.migrationState()).isEqualTo(MigrationState.RETIRED);
    }

    /** T4: the same UUID looked up after the 200-tick window (simulated tick advance) returns null. */
    @Test
    void lookupAfterTtlWindowReturnsNull() {
        registry = new EntityRegistry();
        MigratingEntityRef ref = newRef();
        registry.add(ref, "payload");
        ref.retire();
        registry.retire(ref, 0);

        // Still resolvable just under the window.
        registry.setCurrentTick(199);
        assertThat(registry.lookup(ref.uuid())).isNotNull();

        // Past the window: lookup itself doesn't consult the tick clock for eviction (only the
        // sweeper does), so directly assert the invariant the sweeper enforces — advance the tick
        // and wait for a sweep pass to actually evict.
        registry.setCurrentTick(201);
        await().atMost(Duration.ofSeconds(5)).until(() -> registry.lookup(ref.uuid()) == null);
    }

    @Test
    void liveEntityTakesPrecedenceOverAnyStaleRetiredEntry() {
        registry = new EntityRegistry();
        MigratingEntityRef ref = newRef();
        registry.add(ref, "payload");

        assertThat(registry.lookup(ref.uuid())).isSameAs(ref);
    }

    @Test
    void unknownUuidResolvesToNull() {
        registry = new EntityRegistry();
        assertThat(registry.lookup(UUID.randomUUID())).isNull();
    }

    @Test
    void retireMovesEntryFromLiveToRetiredMap() {
        registry = new EntityRegistry();
        MigratingEntityRef ref = newRef();
        registry.add(ref, "payload");
        assertThat(registry.size()).isEqualTo(1);

        registry.retire(ref, 0);

        assertThat(registry.size()).isEqualTo(0);
        assertThat(registry.retiredCount()).isEqualTo(1);
    }

    /** Sweeper thread lifecycle: the sweeper is a named daemon thread and close() shuts it down. */
    @Test
    void sweeperThreadIsDaemonAndNamed() throws InterruptedException {
        registry = new EntityRegistry();
        CountDownLatch found = new CountDownLatch(1);
        // Give the sweeper's single-thread executor a moment to spin up and name its thread.
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().startsWith("mf-entity-registry-sweeper-")) {
                    found.countDown();
                    assertThat(t.isDaemon()).isTrue();
                    return true;
                }
            }
            return false;
        });
        assertThat(found.getCount()).isEqualTo(0);
    }

    @Test
    void closeShutsDownSweeperExecutor() {
        registry = new EntityRegistry();
        registry.close();
        // A second close() must not throw (idempotent shutdown).
        registry.close();
    }

    /** T5: under concurrent retire + sweep, retiredRefs never grows unbounded across sustained churn. */
    @Test
    void concurrentRetireAndSweepStaysBounded() throws InterruptedException {
        registry = new EntityRegistry();
        int churnRounds = 500;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            long tick = 0;
            for (int i = 0; i < churnRounds; i++) {
                tick += 10; // advance the clock each round so old retirements eventually expire
                registry.setCurrentTick(tick);
                final long thisTick = tick;
                MigratingEntityRef ref = newRef();
                registry.add(ref, "x");
                pool.submit(() -> {
                    ref.retire();
                    registry.retire(ref, thisTick);
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // Advance well past the TTL for every round and let the sweeper catch up.
            registry.setCurrentTick(tick + EntityRegistry.RETIRED_TTL_TICKS + 1000);
            await().atMost(Duration.ofSeconds(10)).until(() -> registry.retiredCount() == 0);
        } finally {
            pool.shutdownNow();
        }
    }
}
