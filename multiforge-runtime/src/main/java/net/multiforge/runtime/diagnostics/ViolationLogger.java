/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.diagnostics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
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
            return parsed <= 0 ? DEFAULT_PER_MIN : parsed;
        } catch (NumberFormatException e) {
            return DEFAULT_PER_MIN;
        }
    }

    private static final ConcurrentMap<String, Bucket> BUCKETS = new ConcurrentHashMap<>();

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
        } else if (dropped == 1L) {
            LOG.warn(
                    "[{}] {} (further identical messages suppressed for {}s)",
                    site,
                    detail,
                    WINDOW_NANOS / 1_000_000_000L);
        }
        // dropped > 1 → silent
    }

    /** Test-only: clears all buckets so per-test rate windows don't leak. */
    public static void resetForTesting() {
        BUCKETS.clear();
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
