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
package net.multiforge.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.runtime.ownership.OwnerToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SerialLaneTest {

    private Thread lane;
    private final AtomicBoolean pumping = new AtomicBoolean(true);

    @BeforeEach
    void startLane() {
        lane = new Thread(
                () -> {
                    while (pumping.get()) {
                        if (!SerialLane.drain()) java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
                    }
                },
                "test-lane");
        lane.start();
        SerialLane.bind(lane);
    }

    @AfterEach
    void stopLane() throws InterruptedException {
        pumping.set(false);
        lane.join(5000);
        SerialLane.unbind();
    }

    @Test
    void regionWorkerJobsRunOnTheLaneUnderTheWorkersToken() {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicReference<OwnerToken> token = new AtomicReference<>();
        OwnerToken.runAs(
                OwnerToken.forRegion(42L),
                () -> SerialLane.run(() -> {
                    ranOn.set(Thread.currentThread());
                    token.set(OwnerToken.current());
                }));
        assertThat(ranOn.get()).isSameAs(lane);
        assertThat(token.get().regionId()).isEqualTo(42L);
    }

    @Test
    void jobsFromManyWorkersRunOneAtATime() throws InterruptedException {
        java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Integer> maxSeen = new CopyOnWriteArrayList<>();
        Thread[] workers = new Thread[8];
        for (int i = 0; i < workers.length; i++) {
            long region = i;
            workers[i] = new Thread(() -> OwnerToken.runAs(OwnerToken.forRegion(region), () -> {
                for (int j = 0; j < 50; j++) {
                    SerialLane.run(() -> {
                        maxSeen.add(inside.incrementAndGet());
                        inside.decrementAndGet();
                    });
                }
            }));
            workers[i].start();
        }
        for (Thread w : workers) w.join(10_000);
        assertThat(maxSeen).hasSize(400).allMatch(n -> n == 1);
    }

    @Test
    void anExceptionReachesTheWorker() {
        assertThatThrownBy(() -> OwnerToken.runAs(
                        OwnerToken.forRegion(1L),
                        () -> SerialLane.run(() -> {
                            throw new IllegalStateException("boom");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void callersWithoutARegionTokenRunInline() {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        SerialLane.run(() -> ranOn.set(Thread.currentThread()));
        assertThat(ranOn.get()).isSameAs(Thread.currentThread());
    }
}
