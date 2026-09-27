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

import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.event.DispatchDomain;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.ownership.OwnerToken;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.SubscribeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DispatchingEventBusTest {

    @BeforeEach
    void resetSharedState() {
        AnnotationScanner.resetForTesting();
        ProbeRegistry.resetForTesting();
        ViolationLogger.resetForTesting();
        StaticListener.CALLS.set(0);
    }

    @Test
    void registerScansInstanceSubscribeEventMethodsAndInvokesThem() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        InstanceListener listener = new InstanceListener();

        bus.register(listener);
        bus.post(new TestEvent());

        assertThat(listener.calls.get()).isEqualTo(1);
    }

    @Test
    void registerScansStaticEventBusSubscriberClassMethods() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);

        bus.register(StaticListener.class);
        bus.post(new TestEvent());

        assertThat(StaticListener.CALLS.get()).isEqualTo(1);
    }

    @Test
    void registerHonorsDispatchDomainAnnotations() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        RegionRoutedListener region = new RegionRoutedListener();
        GlobalRoutedListener global = new GlobalRoutedListener();
        bus.register(region);
        bus.register(global);

        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));

        assertThat(region.calls.get()).isEqualTo(1);
        assertThat(global.calls.get()).isEqualTo(1);
        assertThat(executor.serial.get()).isEqualTo(1); // only the GLOBAL listener used the lane
    }

    @Test
    void unannotatedListenerOfAnUnmappedEventRunsOnTheSerialLane() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        InstanceListener listener = new InstanceListener();
        bus.register(listener);

        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));

        assertThat(listener.calls.get()).isEqualTo(1);
        assertThat(executor.serial.get()).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.serial")).isEqualTo(1);
    }

    @Test
    void lambdaListenersAreRoutedWithTheirResolvedEventType() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Consumer<TestEvent> listener = (TestEvent event) -> calls.incrementAndGet();

        bus.addListener(listener);
        assertThat(DispatchingEventBus.eventTypeOf(listener)).isEqualTo(TestEvent.class);
        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));
        bus.post(new OtherEvent()); // a different event type does not reach it

        assertThat(calls.get()).isEqualTo(1);
        assertThat(executor.serial.get()).isEqualTo(1);
    }

    @Test
    void strictSafeModsRunUnannotatedListenersInline() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        InstanceListener listener = new InstanceListener();
        bus.register(listener);
        ModClassifier.bind(c -> c == InstanceListener.class ? ModSafety.STRICT_SAFE : ModSafety.HYBRID_SAFE);
        try {
            OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));
        } finally {
            ModClassifier.reset();
        }
        assertThat(listener.calls.get()).isEqualTo(1);
        assertThat(executor.serial.get()).isZero();
    }

    @Test
    void unregisterRemovesWrappedListeners() {
        DispatchingEventBus bus =
                new DispatchingEventBus(BusBuilder.builder().build(), new RecordingDispatchExecutor());
        InstanceListener listener = new InstanceListener();
        AtomicInteger lambdaCalls = new AtomicInteger();
        java.util.function.Consumer<TestEvent> lambda = event -> lambdaCalls.incrementAndGet();
        bus.register(listener);
        bus.addListener(TestEvent.class, lambda);

        bus.unregister(listener);
        bus.unregister(lambda);
        bus.post(new TestEvent());

        assertThat(listener.calls.get()).isZero();
        assertThat(lambdaCalls.get()).isZero();
    }

    @Test
    void isEnabledReflectsTheEventDispatchSystemProperty() {
        String prior = System.getProperty(DispatchingEventBus.DISABLE_PROPERTY);
        try {
            System.clearProperty(DispatchingEventBus.DISABLE_PROPERTY);
            DispatchingEventBus enabledBus =
                    new DispatchingEventBus(BusBuilder.builder().build(), new RecordingDispatchExecutor());
            assertThat(enabledBus.isEnabled()).isTrue();

            System.setProperty(DispatchingEventBus.DISABLE_PROPERTY, "off");
            DispatchingEventBus disabledBus =
                    new DispatchingEventBus(BusBuilder.builder().build(), new RecordingDispatchExecutor());
            assertThat(disabledBus.isEnabled()).isFalse();
        } finally {
            if (prior == null) {
                System.clearProperty(DispatchingEventBus.DISABLE_PROPERTY);
            } else {
                System.setProperty(DispatchingEventBus.DISABLE_PROPERTY, prior);
            }
        }
    }

    @Test
    void anEventWithSeveralSerialListenersIsHandedToTheLaneOnce() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        InstanceListener first = new InstanceListener();
        InstanceListener second = new InstanceListener();
        GlobalRoutedListener third = new GlobalRoutedListener();
        RegionRoutedListener region = new RegionRoutedListener();
        bus.register(first);
        bus.register(second);
        bus.register(third);
        bus.register(region);

        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));

        assertThat(first.calls.get() + second.calls.get() + third.calls.get() + region.calls.get())
                .isEqualTo(4);
        assertThat(executor.serial.get()).isEqualTo(1); // one hand-off for the whole post
        assertThat(ProbeRegistry.get("event.dispatch.serial-post")).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.serial")).isEqualTo(3);
        assertThat(ProbeRegistry.get("event.dispatch.serial.event." + TestEvent.class.getName()))
                .isEqualTo(3);
    }

    @Test
    void anEventWithOnlyRegionListenersStaysOnTheWorker() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        RegionRoutedListener region = new RegionRoutedListener();
        InstanceListener other = new InstanceListener(); // serial, but for TestEvent only
        bus.register(region);

        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));
        assertThat(executor.serial.get()).isZero();

        // A serial listener registered later is picked up: the cache is dropped.
        bus.register(other);
        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));
        assertThat(executor.serial.get()).isEqualTo(1);

        bus.unregister(other);
        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));
        assertThat(executor.serial.get()).isEqualTo(1);
        assertThat(region.calls.get()).isEqualTo(3);
    }

    @Test
    void anEventTypeDefaultRegisteredAfterTheListenerApplies() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        InstanceListener listener = new InstanceListener();
        bus.register(listener); // unmapped event: serial
        try {
            EventTypeDomainMap.register(OtherEvent.class.getName(), DispatchDomainKind.REGION);
            EventTypeDomainMap.register(TestEvent.class.getName(), DispatchDomainKind.REGION);

            OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));

            assertThat(listener.calls.get()).isEqualTo(1);
            assertThat(executor.serial.get()).isZero();
        } finally {
            EventTypeDomainMap.resetForTesting();
        }
    }

    @Test
    void postsOffARegionWorkerAreNotBatched() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        InstanceListener listener = new InstanceListener();
        bus.register(listener);

        bus.post(new TestEvent());

        assertThat(listener.calls.get()).isEqualTo(1);
        assertThat(executor.serial.get()).isZero();
        assertThat(ProbeRegistry.get("event.dispatch.serial-post")).isZero();
    }

    private static final class TestEvent extends Event {}

    private static final class InstanceListener {
        final AtomicInteger calls = new AtomicInteger();

        @SubscribeEvent
        void onEvent(TestEvent event) {
            calls.incrementAndGet();
        }
    }

    private static final class RegionRoutedListener {
        final AtomicInteger calls = new AtomicInteger();

        @SubscribeEvent
        @DispatchDomain(DispatchDomainKind.REGION)
        void onEvent(TestEvent event) {
            calls.incrementAndGet();
        }
    }

    private static final class GlobalRoutedListener {
        final AtomicInteger calls = new AtomicInteger();

        @SubscribeEvent
        @DispatchDomain(DispatchDomainKind.GLOBAL)
        public void on(TestEvent event) {
            calls.incrementAndGet();
        }
    }

    private static final class OtherEvent extends Event {}

    private static final class StaticListener {
        static final AtomicInteger CALLS = new AtomicInteger();

        @SubscribeEvent
        static void onEvent(TestEvent event) {
            CALLS.incrementAndGet();
        }
    }

    private static final class RecordingDispatchExecutor implements DispatchExecutor {
        /** Hand-offs to the lane: like SerialLane, a job run from inside a job runs directly. */
        final AtomicInteger serial = new AtomicInteger();

        private int depth;

        @Override
        public void runSerial(Runnable task) {
            if (depth == 0) serial.incrementAndGet();
            depth++;
            try {
                task.run();
            } finally {
                depth--;
            }
        }

        @Override
        public void enqueueGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void enqueueAsync(Runnable task) {
            task.run();
        }
    }
}
