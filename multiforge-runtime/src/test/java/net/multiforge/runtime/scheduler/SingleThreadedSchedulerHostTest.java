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
package net.multiforge.runtime.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ScheduledTask;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.scheduler.TaskState;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SingleThreadedSchedulerHostTest {

    private static final ModIdentifier MOD = ModIdentifier.of("multiforge_test");
    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");
    private static final ChunkPos POS = new ChunkPos(0, 0);

    private SingleThreadedSchedulerHost host;

    @BeforeEach
    void install() {
        ServerDomains.resetForTesting();
        host = new SingleThreadedSchedulerHost();
        host.install();
    }

    @AfterEach
    void shutdown() {
        host.shutdown();
        ServerDomains.resetForTesting();
    }

    @Test
    void regionExecuteRunsAndFinishes() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        ScheduledTask t = ServerDomains.region(WORLD, POS).execute(MOD, ran::countDown);
        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(1)).until(() -> t.state() == TaskState.FINISHED);
    }

    @Test
    void globalExecuteRunsAndFinishes() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        ScheduledTask t = ServerDomains.global().execute(MOD, ran::countDown);
        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(1)).until(() -> t.state() == TaskState.FINISHED);
    }

    @Test
    void cancelPendingBeforeRunTransitionsToCancelled() {
        ScheduledTask t = ServerDomains.global().runDelayed(MOD, ignored -> {}, 10_000L);
        assertThat(t.cancel()).isTrue();
        assertThat(t.state()).isEqualTo(TaskState.CANCELLED);
        // Second cancel returns false.
        assertThat(t.cancel()).isFalse();
    }

    @Test
    void repeatingTaskFiresMultipleTimesThenStopsOnCancel() throws Exception {
        AtomicInteger count = new AtomicInteger();
        AtomicReference<ScheduledTask> ref = new AtomicReference<>();
        ScheduledTask t = ServerDomains.global()
                .runAtFixedRate(
                        MOD,
                        handle -> {
                            ref.set(handle);
                            if (count.incrementAndGet() >= 3) handle.cancel();
                        },
                        0L,
                        1L);
        await().atMost(Duration.ofSeconds(3)).until(() -> count.get() >= 3);
        Thread.sleep(150);
        assertThat(count.get()).isEqualTo(3);
        assertThat(t.state()).isIn(TaskState.CANCELLED, TaskState.CANCELLED_RUNNING);
    }

    @Test
    void asyncCancelTasksByModCancelsPending() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        ServerDomains.async().runDelayed(MOD, ignored -> ran.set(true), 10, TimeUnit.SECONDS);
        int cancelled = ServerDomains.async().cancelTasks(MOD);
        assertThat(cancelled).isEqualTo(1);
        Thread.sleep(200);
        assertThat(ran.get()).isFalse();
    }

    @Test
    void entityScheduleFiresRetiredCallbackWhenEntityRemoved() throws Exception {
        StubEntity entity = new StubEntity();
        entity.retired.set(true);

        CountDownLatch retiredLatch = new CountDownLatch(1);
        CountDownLatch bodyLatch = new CountDownLatch(1);
        ServerDomains.entity(entity).run(MOD, ignored -> bodyLatch.countDown(), retiredLatch::countDown);

        assertThat(retiredLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(bodyLatch.getCount()).isEqualTo(1);
    }

    @Test
    void entityScheduleRunsBodyWhenEntityLive() throws Exception {
        StubEntity entity = new StubEntity();

        CountDownLatch bodyLatch = new CountDownLatch(1);
        ServerDomains.entity(entity).run(MOD, ignored -> bodyLatch.countDown(), () -> {});

        assertThat(bodyLatch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private static final class StubEntity implements EntityRef {
        private final UUID id = UUID.randomUUID();
        private final AtomicBoolean retired = new AtomicBoolean();

        @Override
        public UUID uuid() {
            return id;
        }

        @Override
        public WorldRef world() {
            return WORLD;
        }

        @Override
        public ChunkPos chunkPos() {
            return POS;
        }

        @Override
        public boolean isRetired() {
            return retired.get();
        }
    }
}
