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
package net.multiforge.runtime.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OwnershipEnforcer#soleWriter}: a region the server thread ticks itself
 * (one region in its level, a hot region, the global region) holds a region
 * token but runs while no worker does. Its writes and moves must run inline.
 * Rerouting them handed the work back to the server thread, which ran it at
 * once and re-entered the guard until the stack overflowed (a command block in
 * a single-region world crashed the server).
 */
class OwnershipEnforcerSoleWriterTest {

    private static final WorldRef WORLD = WorldRef.of("test:sole-writer");

    /** Region 1 owns chunk x 0; chunk x 50 is owned by region 2; x >= 100 by nobody. */
    private final OwnershipEnforcer.PositionRouter router = new OwnershipEnforcer.PositionRouter() {
        @Override
        public long ownerOf(WorldRef world, int chunkX, int chunkZ) {
            if (chunkX >= 100) return UNOWNED;
            return chunkX >= 50 ? 2L : 1L;
        }

        @Override
        public boolean queueOnOwner(WorldRef world, int chunkX, int chunkZ, Runnable mutation) {
            return ownerOf(world, chunkX, chunkZ) != UNOWNED;
        }
    };

    private final AtomicInteger rerouted = new AtomicInteger();

    @BeforeEach
    void bind() {
        RegionPhase.resetForTesting();
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.REROUTE);
        OwnershipEnforcer.bindPositionRouter(router);
        OwnershipEnforcer.bindTickThread(Thread.currentThread());
        OwnershipEnforcer.bindRerouteTarget(r -> rerouted.incrementAndGet());
    }

    @AfterEach
    void unbind() {
        RegionPhase.resetForTesting();
        OwnershipEnforcer.unbindPositionRouter();
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.REROUTE);
        OwnershipEnforcer.unbindTickThreadAndRerouteTarget();
    }

    private static boolean asRegion(long id, java.util.function.BooleanSupplier check) {
        AtomicBoolean out = new AtomicBoolean();
        OwnerToken.runAs(OwnerToken.forRegion(id), () -> out.set(check.getAsBoolean()));
        return out.get();
    }

    @Test
    void tickThreadWithNoWorkersIsTheSoleWriter() {
        assertThat(OwnershipEnforcer.soleWriter()).isTrue();
        RegionPhase.beginWorkers();
        assertThat(OwnershipEnforcer.soleWriter()).isFalse();
        RegionPhase.endWorkers();
        assertThat(OwnershipEnforcer.soleWriter()).isTrue();
    }

    @Test
    void anotherThreadIsNeverTheSoleWriter() throws Exception {
        AtomicBoolean seen = new AtomicBoolean(true);
        Thread t = new Thread(() -> seen.set(OwnershipEnforcer.soleWriter()));
        t.start();
        t.join();
        assertThat(seen).isFalse();
    }

    @Test
    void inlineRegionMayWriteUnownedAndForeignChunks() {
        // Before the fix both returned false and the caller rerouted to the server
        // thread — itself — which recursed.
        assertThat(asRegion(1L, () -> OwnershipEnforcer.canMutateAt("site", WORLD, 100, 0)))
                .as("unowned chunk")
                .isTrue();
        assertThat(asRegion(1L, () -> OwnershipEnforcer.canMutateAt("site", WORLD, 50, 0)))
                .as("chunk of another region")
                .isTrue();
        assertThat(asRegion(1L, () -> OwnershipEnforcer.isCrossRegionFromWorker(WORLD, 100, 0)))
                .as("a move into an unowned chunk is not cross-region for the sole writer")
                .isFalse();
        assertThat(rerouted).hasValue(0);
    }

    @Test
    void serialLaneJobWhileWorkersRunIsStillGuarded() {
        // A serial-lane job runs on the server thread under the posting worker's token
        // while workers run; it must keep the worker's view of ownership.
        RegionPhase.beginWorkers();
        try {
            assertThat(asRegion(1L, () -> OwnershipEnforcer.canMutateAt("site", WORLD, 0, 0)))
                    .isTrue();
            assertThat(asRegion(1L, () -> OwnershipEnforcer.canMutateAt("site", WORLD, 50, 0)))
                    .isFalse();
            assertThat(asRegion(1L, () -> OwnershipEnforcer.isCrossRegionFromWorker(WORLD, 100, 0)))
                    .isTrue();
        } finally {
            RegionPhase.endWorkers();
        }
    }

    @Test
    void workerThreadIsGuardedEvenWithNoPhaseFlag() throws Exception {
        AtomicBoolean foreign = new AtomicBoolean(true);
        Thread worker =
                new Thread(() -> foreign.set(asRegion(1L, () -> OwnershipEnforcer.canMutateAt("site", WORLD, 50, 0))));
        worker.start();
        worker.join();
        assertThat(foreign).isFalse();
    }
}
