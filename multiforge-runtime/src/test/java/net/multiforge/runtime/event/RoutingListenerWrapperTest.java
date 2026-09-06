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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.OrderingContract;
import net.multiforge.runtime.event.AnnotationScanner.MetadataEntry;
import net.multiforge.runtime.region.RegionId;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import org.junit.jupiter.api.Test;

class RoutingListenerWrapperTest {

    private static final MetadataEntry LEGACY_SERIAL =
            new MetadataEntry(DispatchDomainKind.LEGACY_SERIAL, OrderingContract.PER_REGION);

    @Test
    void wrapsAndDelegatesThroughARealEventBus() {
        IEventBus bus = BusBuilder.builder().build();
        DomainDispatcher dispatcher = new DomainDispatcher(new InlineDispatchExecutor());
        AtomicInteger received = new AtomicInteger();

        RoutingListenerWrapper<TestEvent> wrapper =
                new RoutingListenerWrapper<>(event -> received.incrementAndGet(), LEGACY_SERIAL, dispatcher);
        bus.addListener(TestEvent.class, wrapper);

        bus.post(new TestEvent());

        assertThat(received.get()).isEqualTo(1);
    }

    @Test
    void dispatchIsInvokedExactlyOncePerAccept() {
        DomainDispatcher dispatcher = new DomainDispatcher(new InlineDispatchExecutor());
        AtomicInteger delegateCalls = new AtomicInteger();
        RoutingListenerWrapper<TestEvent> wrapper =
                new RoutingListenerWrapper<>(event -> delegateCalls.incrementAndGet(), LEGACY_SERIAL, dispatcher);

        wrapper.accept(new TestEvent());

        assertThat(delegateCalls.get()).isEqualTo(1);
    }

    @Test
    void exceptionFromDelegatePropagatesUnswallowed() {
        DomainDispatcher dispatcher = new DomainDispatcher(new InlineDispatchExecutor());
        RoutingListenerWrapper<TestEvent> wrapper = new RoutingListenerWrapper<>(
                event -> {
                    throw new IllegalStateException("boom");
                },
                LEGACY_SERIAL,
                dispatcher);

        assertThatThrownBy(() -> wrapper.accept(new TestEvent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void wrapperRemainsUsableAfterAPriorThrow() {
        DomainDispatcher dispatcher = new DomainDispatcher(new InlineDispatchExecutor());
        AtomicInteger calls = new AtomicInteger();
        RoutingListenerWrapper<TestEvent> wrapper = new RoutingListenerWrapper<>(
                event -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new RuntimeException("first call fails");
                    }
                },
                LEGACY_SERIAL,
                dispatcher);

        assertThatThrownBy(() -> wrapper.accept(new TestEvent())).isInstanceOf(RuntimeException.class);
        // A prior throw must not corrupt the wrapper's (stateless) fields — the next accept() works normally.
        wrapper.accept(new TestEvent());

        assertThat(calls.get()).isEqualTo(2);
    }

    /** Minimal concrete {@code Event} subclass — {@code Event}'s constructor is protected. */
    private static final class TestEvent extends Event {}

    private static final class InlineDispatchExecutor implements DispatchExecutor {
        @Override
        public void enqueueRegion(RegionId destination, Runnable task) {
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
            return Optional.empty();
        }
    }
}
