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

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.multiforge.api.event.DispatchDomain;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.Ordering;
import net.multiforge.api.event.OrderingContract;

/**
 * Reads {@link DispatchDomain} and {@link Ordering} off an {@code
 * @SubscribeEvent} method. Domain resolution is a three-tier fallback:
 *
 * <ol>
 *   <li>Method-level {@link DispatchDomain}.
 *   <li>Class-level {@link DispatchDomain} (the {@code
 *       @EventBusSubscriber} class-level case).
 *   <li><b>{@link EventTypeDomainMap}</b> — if neither annotation is
 *       present, look up the method's single {@code Event}-typed parameter
 *       in {@link EventTypeDomainMap#lookup(Class)}. This gives every
 *       listener for the ~30 highest-value NeoForge events (see {@code
 *       docs/events.md}) a sensible default without requiring the mod
 *       author to annotate anything.
 * </ol>
 *
 * Finally falls back to {@link DispatchDomainKind#LEGACY_SERIAL} if none of
 * the three tiers produced a match. {@link Ordering} resolution is a
 * two-tier fallback (method, then class) with a fixed default of {@link
 * OrderingContract#PER_REGION} — {@link EventTypeDomainMap} has no ordering
 * counterpart; ordering defaults are deliberately left uncontroversial.
 *
 * <p>See {@code docs/design/m12-event-routing.md} §4.2. Pure reflection on
 * the {@code multiforge-api} annotation types — no NeoForge/bus dependency.
 *
 * <p>Results are cached per {@link Method}: reflective {@code Method}
 * objects are stable for the JVM's lifetime once a class is loaded, so a
 * plain identity-keyed map (not a {@code WeakHashMap}) is correct and
 * avoids re-resolving on every registration lookup.
 */
public final class AnnotationScanner {

    /** The dispatch domain and ordering contract resolved for one listener method. */
    public record MetadataEntry(DispatchDomainKind domain, OrderingContract ordering) {
        public MetadataEntry {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(ordering, "ordering");
        }
    }

    private static final ConcurrentMap<Method, MetadataEntry> CACHE = new ConcurrentHashMap<>();

    private AnnotationScanner() {}

    /**
     * Resolves (and caches) the {@link MetadataEntry} for {@code method}.
     * Safe to call repeatedly — the scan only actually runs once per
     * distinct {@link Method}.
     */
    public static MetadataEntry scan(Method method) {
        Objects.requireNonNull(method, "method");
        return CACHE.computeIfAbsent(method, AnnotationScanner::resolve);
    }

    /** Test/diagnostics only: drops every cached entry. */
    public static void resetForTesting() {
        CACHE.clear();
    }

    private static MetadataEntry resolve(Method method) {
        DispatchDomain domainAnn = method.getAnnotation(DispatchDomain.class);
        if (domainAnn == null) {
            domainAnn = method.getDeclaringClass().getAnnotation(DispatchDomain.class);
        }
        DispatchDomainKind domain;
        if (domainAnn != null) {
            domain = domainAnn.value();
        } else {
            // Tier 3: no method- or class-level annotation — fall back to the
            // event-type default map before finally defaulting to LEGACY_SERIAL.
            domain = eventTypeDefault(method).orElse(DispatchDomainKind.LEGACY_SERIAL);
        }

        Ordering orderingAnn = method.getAnnotation(Ordering.class);
        if (orderingAnn == null) {
            orderingAnn = method.getDeclaringClass().getAnnotation(Ordering.class);
        }
        OrderingContract ordering = orderingAnn != null ? orderingAnn.value() : OrderingContract.PER_REGION;

        return new MetadataEntry(domain, ordering);
    }

    /**
     * Looks up {@link EventTypeDomainMap} for the method's single {@code
     * Event}-typed parameter. {@code @SubscribeEvent} methods are required
     * to declare exactly one such parameter ({@link DispatchingEventBus}
     * validates this before ever calling {@link #scan}), but this method is
     * defensive about arity so it can't throw on an unexpected shape — it
     * simply declines to produce a tier-3 default.
     */
    private static Optional<DispatchDomainKind> eventTypeDefault(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 1) {
            return Optional.empty();
        }
        return EventTypeDomainMap.lookup(parameterTypes[0]);
    }
}
