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
import net.multiforge.api.event.DispatchDomainKind;
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
    private final Class<?> listenerClass;
    /** The event type the listener was registered for, or null to keep {@code metadata}'s domain. */
    private final Class<?> eventType;

    /** The resolved domain and the {@link RoutingEpoch} it was resolved in. */
    private volatile Resolved resolved;

    private record Resolved(int epoch, DispatchDomainKind domain) {}

    RoutingListenerWrapper(Consumer<T> delegate, MetadataEntry metadata, DomainDispatcher dispatcher) {
        this(delegate, metadata, dispatcher, delegate.getClass());
    }

    /** @param listenerClass the class whose mod decides the {@link ModSafety} of an unannotated listener */
    RoutingListenerWrapper(
            Consumer<T> delegate, MetadataEntry metadata, DomainDispatcher dispatcher, Class<?> listenerClass) {
        this(delegate, metadata, dispatcher, listenerClass, null);
    }

    /**
     * @param eventType the event type the listener is registered for: an
     *     unannotated listener takes its default domain from {@link
     *     EventTypeDomainMap} again whenever the map changes (an operator
     *     override read at server start applies to listeners registered at
     *     mod construction)
     */
    RoutingListenerWrapper(
            Consumer<T> delegate,
            MetadataEntry metadata,
            DomainDispatcher dispatcher,
            Class<?> listenerClass,
            Class<?> eventType) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.listenerClass = Objects.requireNonNull(listenerClass, "listenerClass");
        this.eventType = eventType;
    }

    @Override
    public void accept(T event) {
        dispatcher.dispatch(event, domain(), metadata.ordering(), () -> delegate.accept(event), listenerClass);
    }

    /** The domain this listener dispatches to now. */
    DispatchDomainKind domain() {
        int epoch = RoutingEpoch.current();
        Resolved r = resolved;
        if (r == null || r.epoch() != epoch) {
            r = new Resolved(epoch, resolve());
            resolved = r;
        }
        return r.domain();
    }

    /** Whether a region worker posting this listener's event must wait for the serial lane. */
    boolean routesSerial() {
        return DomainDispatcher.isSerial(domain(), metadata.ordering());
    }

    Class<?> eventType() {
        return eventType;
    }

    private DispatchDomainKind resolve() {
        if (metadata.explicit()) return metadata.domain();
        MetadataEntry base = eventType == null
                ? metadata
                : new MetadataEntry(
                        EventTypeDomainMap.lookup(eventType).orElse(DispatchDomainKind.LEGACY_SERIAL),
                        metadata.ordering(),
                        false);
        return base.effectiveDomain(ModClassifier.safetyOf(listenerClass));
    }
}
