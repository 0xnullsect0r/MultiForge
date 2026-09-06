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

import java.util.Objects;
import java.util.function.Consumer;
import net.multiforge.runtime.event.AnnotationScanner.MetadataEntry;

/**
 * Wraps a raw listener {@link Consumer} (the real bus's {@code
 * Consumer<T>} calling convention — see {@code
 * docs/design/m12-event-routing.md} §3.2) with a routing decision. Every
 * {@link #accept(Object)} call consults the cached {@link MetadataEntry}
 * and hands off to {@link DomainDispatcher#dispatch}, which decides
 * whether {@code delegate} runs inline or is deferred elsewhere.
 *
 * <p>Stateless beyond its three final fields — safe to invoke repeatedly
 * and concurrently (the real bus may call registered listeners from
 * different region-worker threads across different {@code post()} calls).
 * A throw from {@code delegate} during an inline dispatch propagates out
 * of {@link #accept(Object)} exactly as it would have from the unwrapped
 * consumer — this class adds no exception swallowing of its own; the real
 * bus's own per-listener exception handling (and, for deferred
 * invocations, {@code RegionizedTaskQueue.drain}'s catch-and-log) is what
 * isolates one listener's failure from the next.
 */
final class RoutingListenerWrapper<T> implements Consumer<T> {

    private final Consumer<T> delegate;
    private final MetadataEntry metadata;
    private final DomainDispatcher dispatcher;

    RoutingListenerWrapper(Consumer<T> delegate, MetadataEntry metadata, DomainDispatcher dispatcher) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public void accept(T event) {
        dispatcher.dispatch(event, metadata.domain(), metadata.ordering(), () -> delegate.accept(event));
    }
}
