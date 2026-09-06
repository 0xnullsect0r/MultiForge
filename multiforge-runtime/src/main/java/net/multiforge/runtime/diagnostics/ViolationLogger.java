/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.diagnostics;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rate-limited logger for ownership violations. Keyed by
 * {@code <site>:<domain-mismatch>} so a hot violation site does not flood
 * the log; a token-bucket per key allows a small burst then throttles.
 *
 * <p>Default budget: 5 messages / 60 seconds per key. Configurable via
 * {@code -Dmultiforge.violations.warn-per-min=<n>}.
 */
public final class ViolationLogger {

    private static final Logger LOG = LoggerFactory.getLogger("multiforge.violation");
    private static final long WINDOW_NANOS = 60L * 1_000_000_000L;
    private static final long DEFAULT_PER_MIN = 5L;
    private static final long PER_MIN = parsePerMin(System.getProperty("multiforge.violations.warn-per-min"));

    /**
     * Robust parse that never throws at class-init time — an invalid
     * sysprop falls back to {@link #DEFAULT_PER_MIN} instead of an
     * {@code ExceptionInInitializerError} that would break every
     * OwnershipEnforcer call site (same failure shape as
     * {@link net.multiforge.runtime.region.RegionTickWatchdog#parseWarnMs}).
     */
    public static long parsePerMin(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_PER_MIN;
        try {
            long parsed = Long.parseLong(raw.trim());
            // Accept 0 as a legitimate "silence all warnings" idiom; only
            // negative values (nonsensical) fall back. See /67 round-2 finding.
            return parsed < 0 ? DEFAULT_PER_MIN : parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_PER_MIN;
        }
    }

    private static final ConcurrentMap<String, Bucket> BUCKETS = new ConcurrentHashMap<>();

    // Track C1 (M6): subscribers driving VIOLATION_EVENT frames on the
    // multiforge:debug/v1 channel (see ViolationEmitter). CopyOnWriteArrayList
    // because subscribe/unsubscribe is rare (one per emitter install) while
    // notification happens on every fired warn() — cheap iteration, no lock
    // contention with the hot warn() path.
    private static final CopyOnWriteArrayList<Consumer<ViolationEvent>> SUBSCRIBERS = new CopyOnWriteArrayList<>();

    // M6 (C3.8): bounded history backing `/multiforge warn list` / `warn
    // clear`. Follows the same fan-out invariant as SUBSCRIBERS — one entry
    // per emitted WARN log line (dropped == 0 or 1 in #warn below), never
    // per rate-limiter-suppressed call, so the command surface and the
    // debug-client side panel always agree on "what actually got logged."
    private static final int RECENT_CAPACITY = 200;
    private static final Queue<ViolationEvent> RECENT = new ConcurrentLinkedDeque<>();

    private ViolationLogger() {}

    public static void warn(String site, String detail) {
        warn(null, site, detail);
    }

    /**
     * Site + per-mod scoped warn — each mod gets its own budget so a
     * chatty mod can't crowd out other mods' warnings. Pass {@code
     * null} for {@code modId} for site-scoped bucketing (matches the
     * pre-M5 single-arg overload).
     */
    public static void warn(String modId, String site, String detail) {
        String key = modId == null ? site : (modId + "::" + site);
        Bucket b = BUCKETS.computeIfAbsent(key, k -> new Bucket(PER_MIN, WINDOW_NANOS));
        long dropped = b.tryConsume();
        if (dropped == 0L) {
            LOG.warn("[{}] {}", site, detail);
            recordAndNotify(modId, site, detail);
        } else if (dropped == 1L) {
            LOG.warn(
                    "[{}] {} (further identical messages suppressed for {}s)",
                    site,
                    detail,
                    WINDOW_NANOS / 1_000_000_000L);
            recordAndNotify(modId, site, detail);
        }
        // dropped > 1 → silent: no subscriber notification and no
        // /multiforge warn history entry either — see
        // docs/design/client-debug-protocol.md §9 invariant 10: VIOLATION_EVENT
        // fan-out must match ViolationLogger's own rate limiting, i.e. one
        // frame per emitted WARN log line, never per rate-limiter-suppressed call.
    }

    /**
     * Register to receive one {@link ViolationEvent} per emitted WARN
     * (both the normal-fire and the one-time "further suppressed"
     * branches above — never for a silently-dropped call). Used by
     * {@code net.multiforge.runtime.diagnostics.emitters.ViolationEmitter}
     * to drive {@code VIOLATION_EVENT} frames on {@code
     * multiforge:debug/v1}; see {@code docs/design/client-debug-protocol.md}
     * §3, §7.5, §9 invariant 10.
     *
     * @return an {@link AutoCloseable} that unsubscribes {@code handler}
     */
    public static AutoCloseable subscribe(Consumer<ViolationEvent> handler) {
        Objects.requireNonNull(handler, "handler");
        SUBSCRIBERS.add(handler);
        return () -> SUBSCRIBERS.remove(handler);
    }

    private static void recordAndNotify(String modId, String site, String detail) {
        ViolationEvent event = new ViolationEvent(System.currentTimeMillis(), modId, site, detail);
        RECENT.add(event);
        while (RECENT.size() > RECENT_CAPACITY) {
            RECENT.poll();
        }
        if (SUBSCRIBERS.isEmpty()) return;
        for (Consumer<ViolationEvent> s : SUBSCRIBERS) {
            s.accept(event);
        }
    }

    /**
     * Snapshot of the last (at most) {@value #RECENT_CAPACITY} emitted
     * violations, oldest first. Backs {@code /multiforge warn list} (see
     * {@code net.multiforge.runtime.commands.MultiForgeCommandDispatcher}).
     */
    public static List<ViolationEvent> recent() {
        return List.copyOf(RECENT);
    }

    /**
     * Drops the recent-violation history and returns how many entries were
     * cleared. Backs {@code /multiforge warn clear}. Does not touch the
     * rate-limit buckets — a mod that was mid-burst keeps its existing
     * throttle window, only the operator-visible history resets.
     */
    public static int clearRecent() {
        int n = RECENT.size();
        RECENT.clear();
        return n;
    }

    /** Test-only: clears all buckets and recent-violation history so per-test state doesn't leak. */
    public static void resetForTesting() {
        BUCKETS.clear();
        RECENT.clear();
    }

    /** Test-only: drop every subscriber so per-test subscriptions don't leak. */
    public static void clearSubscribersForTesting() {
        SUBSCRIBERS.clear();
    }

    /**
     * One fired warn() call, decoupled from the wire {@code
     * DebugPayload.ViolationEvent} record (this module has no
     * dependency on the wire layer). {@code modId} is nullable — mirrors
     * the site-scoped {@link #warn(String, String)} overload; the
     * consuming {@code ViolationEmitter} substitutes a defined sentinel
     * (empty string) before encoding, since the wire has no null
     * representation (see {@code client-debug-protocol.md} §7.5).
     */
    public record ViolationEvent(long epochMillis, String modId, String site, String detail) {
        public ViolationEvent {
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(detail, "detail");
        }
    }

    private static final class Bucket {
        private final long capacity;
        private final long windowNanos;
        private final AtomicLong count = new AtomicLong();
        private volatile long windowStart = System.nanoTime();

        Bucket(long capacity, long windowNanos) {
            this.capacity = capacity;
            this.windowNanos = windowNanos;
        }

        /**
         * @return 0 = fire, 1 = fire the "suppressing" note, >1 = drop silently.
         */
        long tryConsume() {
            // Capacity 0 = documented "silence all warnings" idiom
            // (-Dmultiforge.violations.warn-per-min=0). Skip the
            // "further suppressed" note branch entirely — the old
            // capacity-plus-one path emitted one WARN per site per
            // window, which contradicted the documented contract.
            // /67 round-3 finding.
            if (capacity == 0L) return 2L;
            long now = System.nanoTime();
            long start = windowStart;
            if (now - start >= windowNanos) {
                // best-effort window reset; concurrent resetters converge
                windowStart = now;
                count.set(0L);
            }
            long n = count.incrementAndGet();
            if (n <= capacity) return 0L;
            if (n == capacity + 1L) return 1L;
            return 2L;
        }
    }
}
