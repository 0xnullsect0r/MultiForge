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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.OrderingContract;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.ownership.OwnerToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The decision table in {@link DomainDispatcher}'s javadoc. */
class DomainDispatcherTest {

    private static final OwnerToken REGION_7 = OwnerToken.forRegion(7L);

    private RecordingDispatchExecutor executor;
    private DomainDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        executor = new RecordingDispatchExecutor();
        dispatcher = new DomainDispatcher(executor);
        ProbeRegistry.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private void dispatchAs(OwnerToken token, DispatchDomainKind domain, OrderingContract ordering, Runnable r) {
        OwnerToken.runAs(token, () -> dispatcher.dispatch(new Object(), domain, ordering, r));
    }

    @Test
    void regionWorkerRunsRegionListenersInline() {
        AtomicReference<Thread> ran = new AtomicReference<>();
        dispatchAs(
                REGION_7,
                DispatchDomainKind.REGION,
                OrderingContract.PER_REGION,
                () -> ran.set(Thread.currentThread()));
        assertThat(ran.get()).isSameAs(Thread.currentThread());
        assertThat(executor.serial.get() + executor.global.get() + executor.async.get())
                .isZero();
    }

    @Test
    void regionWorkerRunsGlobalAndLegacyListenersOnTheSerialLane() {
        AtomicInteger ran = new AtomicInteger();
        dispatchAs(REGION_7, DispatchDomainKind.GLOBAL, OrderingContract.PER_REGION, ran::incrementAndGet);
        dispatchAs(REGION_7, DispatchDomainKind.LEGACY_SERIAL, OrderingContract.PER_REGION, ran::incrementAndGet);
        dispatchAs(REGION_7, DispatchDomainKind.REGION, OrderingContract.GLOBAL_TOTAL, ran::incrementAndGet);
        assertThat(executor.serial.get()).isEqualTo(3);
        assertThat(ran.get()).isEqualTo(3); // the serial lane waits for the listener
        assertThat(ProbeRegistry.get("event.dispatch.serial")).isEqualTo(3);
    }

    @Test
    void regionWorkerHandsAsyncListenersToThePool() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Thread> ran = new AtomicReference<>();
        dispatchAs(REGION_7, DispatchDomainKind.ASYNC, OrderingContract.PER_REGION, () -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isNotSameAs(Thread.currentThread());
        assertThat(executor.async.get()).isEqualTo(1);
    }

    @Test
    void globalTotalOrderingWinsOverAnAsyncDomain() {
        dispatchAs(REGION_7, DispatchDomainKind.ASYNC, OrderingContract.GLOBAL_TOTAL, () -> {});
        assertThat(executor.serial.get()).isEqualTo(1);
        assertThat(executor.async.get()).isZero();
    }

    @Test
    void unboundThreadsRunEveryListenerInline() {
        AtomicInteger ran = new AtomicInteger();
        for (DispatchDomainKind kind : DispatchDomainKind.values()) {
            dispatcher.dispatch(new Object(), kind, OrderingContract.PER_REGION, ran::incrementAndGet);
        }
        assertThat(ran.get()).isEqualTo(DispatchDomainKind.values().length);
        assertThat(executor.serial.get() + executor.global.get() + executor.async.get())
                .isZero();
        assertThat(ProbeRegistry.get("event.dispatch.inline")).isEqualTo(DispatchDomainKind.values().length);
    }

    @Test
    void asyncTasksHandGlobalListenersToTheGlobalRegion() {
        dispatchAs(OwnerToken.ASYNC, DispatchDomainKind.GLOBAL, OrderingContract.PER_REGION, () -> {});
        dispatchAs(OwnerToken.ASYNC, DispatchDomainKind.ASYNC, OrderingContract.PER_REGION, () -> {});
        dispatchAs(OwnerToken.ASYNC, DispatchDomainKind.REGION, OrderingContract.PER_REGION, () -> {});
        assertThat(executor.global.get()).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.inline")).isEqualTo(2);
    }

    /** Serial and global hand-offs run synchronously here; async runs on a real thread. */
    private static final class RecordingDispatchExecutor implements DispatchExecutor {
        final AtomicInteger serial = new AtomicInteger();
        final AtomicInteger global = new AtomicInteger();
        final AtomicInteger async = new AtomicInteger();
        private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();

        @Override
        public void runSerial(Runnable task) {
            serial.incrementAndGet();
            task.run();
        }

        @Override
        public void enqueueGlobal(Runnable task) {
            global.incrementAndGet();
            task.run();
        }

        @Override
        public void enqueueAsync(Runnable task) {
            async.incrementAndGet();
            asyncExecutor.submit(task);
        }

        void close() {
            asyncExecutor.shutdownNow();
        }
    }
}
