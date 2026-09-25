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
        GlobalAnnotatedListener listener = new GlobalAnnotatedListener();
        bus.register(listener);

        // Mirrors the real pre-attach window (mod construction, FMLCommonSetupEvent, ...):
        // no OwnerToken is bound to this thread, so OwnerToken.current() defaults to UNKNOWN
        // and DomainDispatcher's very first check short-circuits straight to inline —
        // matching pre-M12 Vanilla behavior regardless of the listener's declared domain.
        bus.post(new TestEvent());

        assertThat(listener.calls.get()).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.inline")).isEqualTo(1);
        assertThat(ProbeRegistry.get("event.dispatch.serial")).isZero();
    }

    @Test
    void attachExecutorSwitchesAlreadyRegisteredListenersToRealRouting() {
        LazyDispatchingEventBus bus =
                new LazyDispatchingEventBus(BusBuilder.builder().build());
        GlobalAnnotatedListener listener = new GlobalAnnotatedListener();
        bus.register(listener); // registered BEFORE attach — mirrors mod registration before ServerAboutToStart

        RecordingDispatchExecutor executor = new RecordingDispatchExecutor();

        boolean attached = bus.attachExecutor(executor);
        assertThat(attached).isTrue();
        assertThat(bus.isAttached()).isTrue();

        // Posted from a region worker, the GLOBAL listener now uses the attached
        // executor's serial lane, with no re-registration needed.
        OwnerToken.runAs(OwnerToken.forRegion(5L), () -> bus.post(new TestEvent()));

        assertThat(listener.calls.get()).isEqualTo(1);
        assertThat(executor.serial.get()).isEqualTo(1);
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

        GlobalAnnotatedListener listener = new GlobalAnnotatedListener();
        bus.register(listener);
        OwnerToken.runAs(OwnerToken.forRegion(999L), () -> bus.post(new TestEvent()));

        // Routed through the first-attached executor, never the second.
        assertThat(first.serial.get()).isEqualTo(1);
        assertThat(second.serial.get()).isZero();
    }

    private static final class TestEvent extends Event {}

    private static final class GlobalAnnotatedListener {
        final AtomicInteger calls = new AtomicInteger();

        @SubscribeEvent
        @DispatchDomain(DispatchDomainKind.GLOBAL)
        void onEvent(TestEvent event) {
            calls.incrementAndGet();
        }
    }

    private static final class RecordingDispatchExecutor implements DispatchExecutor {
        final AtomicInteger serial = new AtomicInteger();

        @Override
        public void runSerial(Runnable task) {
            serial.incrementAndGet();
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
    }
}
