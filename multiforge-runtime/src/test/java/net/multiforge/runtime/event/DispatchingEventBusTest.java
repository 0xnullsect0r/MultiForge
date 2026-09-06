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
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.event.DispatchDomain;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.region.RegionId;
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
    void registerHonorsDispatchDomainAnnotationForEndToEndRouting() {
        RegionId target = RegionId.next();
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        executor.location = Optional.of(target);
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        RegionRoutedListener listener = new RegionRoutedListener();
        bus.register(listener);

        // Caller is a different region than the event resolves to -> expect a real hand-off.
        OwnerToken.runAs(OwnerToken.forRegion(target.value() + 1), () -> bus.post(new TestEvent()));

        assertThat(listener.calls.get()).isEqualTo(1);
        assertThat(executor.regionEnqueues).containsExactly(target);
    }

    @Test
    void unannotatedSubscribeEventMethodDefaultsToLegacySerialAndWarns() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        InstanceListener listener = new InstanceListener();
        bus.register(listener);

        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));

        assertThat(listener.calls.get()).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.legacy")).isEqualTo(1);
        assertThat(ViolationLogger.recent())
                .anySatisfy(e -> assertThat(e.site()).isEqualTo("DomainDispatcher.legacySerial"));
    }

    @Test
    void addListenerConsumerFamilyIsWrappedAsLegacySerialDefault() {
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        DispatchingEventBus bus = new DispatchingEventBus(BusBuilder.builder().build(), executor);
        AtomicInteger calls = new AtomicInteger();

        bus.addListener(TestEvent.class, event -> calls.incrementAndGet());
        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> bus.post(new TestEvent()));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.legacy")).isEqualTo(1);
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

    private static final class StaticListener {
        static final AtomicInteger CALLS = new AtomicInteger();

        @SubscribeEvent
        static void onEvent(TestEvent event) {
            CALLS.incrementAndGet();
        }
    }

    private static final class RecordingDispatchExecutor implements DispatchExecutor {
        final List<RegionId> regionEnqueues = new CopyOnWriteArrayList<>();
        volatile Optional<RegionId> location = Optional.empty();

        @Override
        public void enqueueRegion(RegionId destination, Runnable task) {
            regionEnqueues.add(destination);
            task.run();
        }

        @Override
        public void enqueueGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void enqueueAsync(Runnable task) {
            task.run();
        }

        @Override
        public Optional<RegionId> resolveEventLocation(Object event) {
            return location;
        }
    }
}
