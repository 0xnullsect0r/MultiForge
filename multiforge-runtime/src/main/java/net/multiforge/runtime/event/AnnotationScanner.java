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

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.multiforge.api.event.DispatchDomain;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.Ordering;
import net.multiforge.api.event.OrderingContract;

/**
 * Reads {@link DispatchDomain} and {@link Ordering} off an {@code
 * @SubscribeEvent} method — method-level annotation wins, falling back to
 * the declaring class's annotation (the {@code @EventBusSubscriber}
 * class-level case), and finally to the documented defaults ({@link
 * DispatchDomainKind#LEGACY_SERIAL}, {@link OrderingContract#PER_REGION}).
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
        DispatchDomainKind domain = domainAnn != null ? domainAnn.value() : DispatchDomainKind.LEGACY_SERIAL;

        Ordering orderingAnn = method.getAnnotation(Ordering.class);
        if (orderingAnn == null) {
            orderingAnn = method.getDeclaringClass().getAnnotation(Ordering.class);
        }
        OrderingContract ordering = orderingAnn != null ? orderingAnn.value() : OrderingContract.PER_REGION;

        return new MetadataEntry(domain, ordering);
    }
}
