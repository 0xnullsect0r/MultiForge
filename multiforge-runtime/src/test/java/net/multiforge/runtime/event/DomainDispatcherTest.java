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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.OrderingContract;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.ownership.Domain;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DomainDispatcherTest {

    private RecordingDispatchExecutor executor;
    private DomainDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        executor = new RecordingDispatchExecutor();
        dispatcher = new DomainDispatcher(executor);
        ProbeRegistry.resetForTesting();
        ViolationLogger.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void regionCallerSameRegionListenerRunsInline() {
        RegionId region = RegionId.next();
        executor.location = Optional.of(region);
        AtomicInteger invoked = new AtomicInteger();

        OwnerToken.runAs(
                OwnerToken.forRegion(region.value()),
                () -> dispatcher.dispatch(
                        new Object(),
                        DispatchDomainKind.REGION,
                        OrderingContract.PER_REGION,
                        invoked::incrementAndGet));

        assertThat(invoked.get()).isEqualTo(1);
        assertThat(executor.regionEnqueues).isEmpty();
        assertThat(ProbeRegistry.get("event.dispatch.inline")).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.region")).isZero();
    }

    @Test
    void regionCallerDifferentRegionListenerEnqueuesToTargetRegion() {
        RegionId callerRegion = RegionId.next();
        RegionId targetRegion = RegionId.next();
        executor.location = Optional.of(targetRegion);
        AtomicInteger invoked = new AtomicInteger();

        OwnerToken.runAs(
                OwnerToken.forRegion(callerRegion.value()),
                () -> dispatcher.dispatch(
                        new Object(),
                        DispatchDomainKind.REGION,
                        OrderingContract.PER_REGION,
                        invoked::incrementAndGet));

        assertThat(executor.regionEnqueues).containsExactly(targetRegion);
        assertThat(invoked.get()).isEqualTo(1); // the fake executor runs the task synchronously
        assertThat(ProbeRegistry.get("event.dispatch.region")).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.inline")).isZero();
    }

    @Test
    void regionCallerGlobalListenerEnqueuesOnGlobalRegion() {
        AtomicInteger invoked = new AtomicInteger();

        OwnerToken.runAs(
                OwnerToken.forRegion(1L),
                () -> dispatcher.dispatch(
                        new Object(),
                        DispatchDomainKind.GLOBAL,
                        OrderingContract.PER_REGION,
                        invoked::incrementAndGet));

        assertThat(executor.globalEnqueueCount.get()).isEqualTo(1);
        assertThat(invoked.get()).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.global")).isEqualTo(1);
    }

    @Test
    void globalCallerRegionListenerWithResolvableLocationEnqueuesToThatRegion() {
        RegionId target = RegionId.next();
        executor.location = Optional.of(target);
        AtomicInteger invoked = new AtomicInteger();

        OwnerToken.runAs(
                OwnerToken.GLOBAL,
                () -> dispatcher.dispatch(
                        new Object(),
                        DispatchDomainKind.REGION,
                        OrderingContract.PER_REGION,
                        invoked::incrementAndGet));

        assertThat(executor.regionEnqueues).containsExactly(target);
        assertThat(ProbeRegistry.get("event.dispatch.region")).isEqualTo(1);
    }

    @Test
    void globalCallerRegionListenerWithNoLocationFallsBackInlineAndWarnsOnce() {
        executor.location = Optional.empty();
        AtomicInteger invoked = new AtomicInteger();

        OwnerToken.runAs(
                OwnerToken.GLOBAL,
                () -> dispatcher.dispatch(
                        new Object(),
                        DispatchDomainKind.REGION,
                        OrderingContract.PER_REGION,
                        invoked::incrementAndGet));

        assertThat(invoked.get()).isEqualTo(1);
        assertThat(executor.regionEnqueues).isEmpty();
        assertThat(executor.globalEnqueueCount.get()).isZero(); // caller was already GLOBAL -> inline, not enqueued
        assertThat(ProbeRegistry.get("event.dispatch.inline")).isEqualTo(1);
        assertThat(ViolationLogger.recent()).hasSize(1);
        assertThat(ViolationLogger.recent().get(0).site()).isEqualTo("DomainDispatcher.noLocation");
    }

    @Test
    void globalCallerGlobalListenerRunsInline() {
        AtomicInteger invoked = new AtomicInteger();

        OwnerToken.runAs(
                OwnerToken.GLOBAL,
                () -> dispatcher.dispatch(
                        new Object(),
                        DispatchDomainKind.GLOBAL,
                        OrderingContract.PER_REGION,
                        invoked::incrementAndGet));

        assertThat(invoked.get()).isEqualTo(1);
        assertThat(executor.globalEnqueueCount.get()).isZero();
        assertThat(ProbeRegistry.get("event.dispatch.inline")).isEqualTo(1);
    }

    @Test
    void anyCallerAsyncListenerRunsOnDifferentThreadViaAsyncPool() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Thread testThread = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        OwnerToken.runAs(
                OwnerToken.forRegion(1L),
                () -> dispatcher.dispatch(new Object(), DispatchDomainKind.ASYNC, OrderingContract.PER_REGION, () -> {
                    ranOn.set(Thread.currentThread());
                    latch.countDown();
                }));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ranOn.get()).isNotSameAs(testThread);
        assertThat(ProbeRegistry.get("event.dispatch.async")).isEqualTo(1);
    }

    @Test
    void anyCallerLegacySerialListenerRunsInlineWithRateLimitedWarn() {
        AtomicInteger invoked = new AtomicInteger();

        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> {
            for (int i = 0; i < 10; i++) {
                dispatcher.dispatch(
                        new Object(),
                        DispatchDomainKind.LEGACY_SERIAL,
                        OrderingContract.PER_REGION,
                        invoked::incrementAndGet);
            }
        });

        // Every firing runs inline regardless of the logger's rate limit.
        assertThat(invoked.get()).isEqualTo(10);
        assertThat(ProbeRegistry.get("event.dispatch.legacy")).isEqualTo(10);
        // The warn itself is rate-limited (default budget: 5/min + 1 "suppressed" notice).
        assertThat(ViolationLogger.recent().size()).isLessThanOrEqualTo(6);
    }

    @Test
    void unknownCallerRunsInlineRegardlessOfListenerDomain() {
        for (DispatchDomainKind domain : DispatchDomainKind.values()) {
            AtomicInteger invoked = new AtomicInteger();
            assertThat(OwnerToken.current().domain()).isEqualTo(Domain.UNKNOWN);
            dispatcher.dispatch(new Object(), domain, OrderingContract.PER_REGION, invoked::incrementAndGet);
            assertThat(invoked.get()).as("domain " + domain).isEqualTo(1);
        }
        assertThat(executor.regionEnqueues).isEmpty();
        assertThat(executor.globalEnqueueCount.get()).isZero();
        assertThat(executor.asyncEnqueueCount.get()).isZero();
    }

    @Test
    void perRegionOrderingRunsTwoListenersInRegistrationOrder() {
        RegionId region = RegionId.next();
        executor.location = Optional.of(region);
        List<String> order = new CopyOnWriteArrayList<>();

        OwnerToken.runAs(OwnerToken.forRegion(999L), () -> {
            dispatcher.dispatch(
                    new Object(), DispatchDomainKind.REGION, OrderingContract.PER_REGION, () -> order.add("A"));
            dispatcher.dispatch(
                    new Object(), DispatchDomainKind.REGION, OrderingContract.PER_REGION, () -> order.add("B"));
        });

        assertThat(order).containsExactly("A", "B");
    }

    @Test
    void globalTotalOrderingForcesGlobalRoutingRegardlessOfDeclaredDomain() {
        RegionId someRegion = RegionId.next();
        executor.location = Optional.of(someRegion);
        AtomicInteger invoked = new AtomicInteger();

        OwnerToken.runAs(
                OwnerToken.forRegion(1L),
                () -> dispatcher.dispatch(
                        new Object(),
                        DispatchDomainKind.REGION,
                        OrderingContract.GLOBAL_TOTAL,
                        invoked::incrementAndGet));

        // GLOBAL_TOTAL wins over the listener's own REGION domain — routed to global, not the region.
        assertThat(executor.regionEnqueues).isEmpty();
        assertThat(executor.globalEnqueueCount.get()).isEqualTo(1);
        assertThat(invoked.get()).isEqualTo(1);
    }

    @Test
    void bestEffortOrderingUsesNormalRoutingWithNoOrderingGuarantee() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);

        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> {
            dispatcher.dispatch(new Object(), DispatchDomainKind.ASYNC, OrderingContract.BEST_EFFORT, latch::countDown);
            dispatcher.dispatch(new Object(), DispatchDomainKind.ASYNC, OrderingContract.BEST_EFFORT, latch::countDown);
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void eventPriorityOrderIsPreservedAcrossDeferralToTheSameRegion() {
        RegionId caller = RegionId.next();
        RegionId target = RegionId.next();
        executor.location = Optional.of(target);
        List<String> order = new CopyOnWriteArrayList<>();

        OwnerToken.runAs(OwnerToken.forRegion(caller.value()), () -> {
            // Simulates the real bus calling wrappers HIGHEST-then-LOWEST within one post().
            dispatcher.dispatch(
                    new Object(), DispatchDomainKind.REGION, OrderingContract.PER_REGION, () -> order.add("HIGHEST"));
            dispatcher.dispatch(
                    new Object(), DispatchDomainKind.REGION, OrderingContract.PER_REGION, () -> order.add("LOWEST"));
        });

        assertThat(executor.regionEnqueues).containsExactly(target, target);
        assertThat(order).containsExactly("HIGHEST", "LOWEST");
    }

    @Test
    void probeRegistryCounterBumpedPerOutcomeBucket() {
        RegionId region = RegionId.next();

        // inline (region, same region as caller)
        executor.location = Optional.of(region);
        OwnerToken.runAs(
                OwnerToken.forRegion(region.value()),
                () -> dispatcher.dispatch(
                        new Object(), DispatchDomainKind.REGION, OrderingContract.PER_REGION, () -> {}));
        // region (different region)
        executor.location = Optional.of(RegionId.next());
        OwnerToken.runAs(
                OwnerToken.forRegion(region.value()),
                () -> dispatcher.dispatch(
                        new Object(), DispatchDomainKind.REGION, OrderingContract.PER_REGION, () -> {}));
        // global
        OwnerToken.runAs(
                OwnerToken.forRegion(region.value()),
                () -> dispatcher.dispatch(
                        new Object(), DispatchDomainKind.GLOBAL, OrderingContract.PER_REGION, () -> {}));
        // async
        OwnerToken.runAs(
                OwnerToken.forRegion(region.value()),
                () -> dispatcher.dispatch(
                        new Object(), DispatchDomainKind.ASYNC, OrderingContract.PER_REGION, () -> {}));
        // legacy
        OwnerToken.runAs(
                OwnerToken.forRegion(region.value()),
                () -> dispatcher.dispatch(
                        new Object(), DispatchDomainKind.LEGACY_SERIAL, OrderingContract.PER_REGION, () -> {}));

        assertThat(ProbeRegistry.get("event.dispatch.inline")).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.region")).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.global")).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.async")).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.legacy")).isEqualTo(1);
    }

    /**
     * Hand-rolled fake — no mocking framework in this module's dependencies. Region/global
     * enqueues run the task synchronously (only the routing decision is under test); async
     * enqueues run on a real background thread so cross-thread assertions are meaningful.
     */
    private static final class RecordingDispatchExecutor implements DispatchExecutor {
        final List<RegionId> regionEnqueues = new CopyOnWriteArrayList<>();
        final AtomicInteger globalEnqueueCount = new AtomicInteger();
        final AtomicInteger asyncEnqueueCount = new AtomicInteger();
        private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();
        volatile Optional<RegionId> location = Optional.empty();

        @Override
        public void enqueueRegion(RegionId destination, Runnable task) {
            regionEnqueues.add(destination);
            task.run();
        }

        @Override
        public void enqueueGlobal(Runnable task) {
            globalEnqueueCount.incrementAndGet();
            task.run();
        }

        @Override
        public void enqueueAsync(Runnable task) {
            asyncEnqueueCount.incrementAndGet();
            asyncExecutor.submit(task);
        }

        @Override
        public Optional<RegionId> resolveEventLocation(Object event) {
            return location;
        }

        void close() {
            asyncExecutor.shutdownNow();
        }
    }
}
