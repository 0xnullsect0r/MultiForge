/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
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

/**
 * Covers M12.2's lazy-attach contract (see {@code
 * docs/design/m12-event-routing.md} §12 and this class's own javadoc):
 * before {@link LazyDispatchingEventBus#attachExecutor} runs, every
 * dispatch is inline regardless of the listener's declared domain; after
 * it runs, dispatch routes through the attached {@link DispatchExecutor}
 * exactly like a plain {@link DispatchingEventBus} would.
 */
class LazyDispatchingEventBusTest {

    @BeforeEach
    void resetSharedState() {
        AnnotationScanner.resetForTesting();
        ProbeRegistry.resetForTesting();
        ViolationLogger.resetForTesting();
    }

    @Test
    void startsUnattached() {
        LazyDispatchingEventBus bus =
                new LazyDispatchingEventBus(BusBuilder.builder().build());
        assertThat(bus.isAttached()).isFalse();
    }

    @Test
    void preAttachDispatchRunsInlineForARegionDomainListenerWithNoBoundOwnerToken() {
        LazyDispatchingEventBus bus =
                new LazyDispatchingEventBus(BusBuilder.builder().build());
        RegionRoutedListener listener = new RegionRoutedListener();
        bus.register(listener);

        // Mirrors the real pre-attach window (mod construction, FMLCommonSetupEvent, ...):
        // no OwnerToken is bound to this thread, so OwnerToken.current() defaults to UNKNOWN
        // and DomainDispatcher's very first check short-circuits straight to inline —
        // matching pre-M12 Vanilla behavior regardless of the listener's declared domain.
        bus.post(new TestEvent());

        assertThat(listener.calls.get()).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.inline")).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.region")).isZero();
        assertThat(ProbeRegistry.get("event.dispatch.global")).isZero();
    }

    @Test
    void attachExecutorSwitchesAlreadyRegisteredListenersToRealRouting() {
        LazyDispatchingEventBus bus =
                new LazyDispatchingEventBus(BusBuilder.builder().build());
        RegionRoutedListener listener = new RegionRoutedListener();
        bus.register(listener); // registered BEFORE attach — mirrors mod registration before ServerAboutToStart

        RegionId target = RegionId.next();
        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();
        executor.location = Optional.of(target);

        boolean attached = bus.attachExecutor(executor);
        assertThat(attached).isTrue();
        assertThat(bus.isAttached()).isTrue();

        // Caller is a different region than the event resolves to -> expect a real hand-off,
        // proving the already-registered listener now routes through the attached executor
        // with no re-registration needed.
        OwnerToken.runAs(OwnerToken.forRegion(target.value() + 1), () -> bus.post(new TestEvent()));

        assertThat(listener.calls.get()).isEqualTo(1);
        assertThat(executor.regionEnqueues).containsExactly(target);
    }

    @Test
    void attachExecutorIsIdempotent() {
        LazyDispatchingEventBus bus =
                new LazyDispatchingEventBus(BusBuilder.builder().build());
        RecordingDispatchExecutor first = new RecordingDispatchExecutor();
        RecordingDispatchExecutor second = new RecordingDispatchExecutor();

        assertThat(bus.attachExecutor(first)).isTrue();
        assertThat(bus.attachExecutor(second)).isFalse(); // no-op — first attach wins
        assertThat(bus.isAttached()).isTrue();

        RegionRoutedListener listener = new RegionRoutedListener();
        bus.register(listener);
        first.location = Optional.of(RegionId.next());
        OwnerToken.runAs(OwnerToken.forRegion(999L), () -> bus.post(new TestEvent()));

        // Routed through the first-attached executor, never the second.
        assertThat(first.regionEnqueues).isNotEmpty();
        assertThat(second.regionEnqueues).isEmpty();
    }

    private static final class TestEvent extends Event {}

    private static final class RegionRoutedListener {
        final AtomicInteger calls = new AtomicInteger();

        @SubscribeEvent
        @DispatchDomain(DispatchDomainKind.REGION)
        void onEvent(TestEvent event) {
            calls.incrementAndGet();
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
