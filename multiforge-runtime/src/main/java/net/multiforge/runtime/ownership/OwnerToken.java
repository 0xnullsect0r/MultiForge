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
package net.multiforge.runtime.ownership;

/**
 * A tag carried by each MultiForge-owned worker thread describing which
 * {@link Domain} it is currently executing in, and (for region workers)
 * which region id it owns.
 *
 * <p>Region worker threads set their own token when they start ticking a
 * region and clear it when they finish. Non-worker threads observe
 * {@code null} here, which the assertion layer treats as {@link
 * Domain#UNKNOWN}.
 */
public record OwnerToken(Domain domain, long regionId) {

    public static final long NO_REGION = Long.MIN_VALUE;
    public static final OwnerToken GLOBAL = new OwnerToken(Domain.GLOBAL, NO_REGION);
    public static final OwnerToken ASYNC = new OwnerToken(Domain.ASYNC, NO_REGION);
    public static final OwnerToken NETWORK = new OwnerToken(Domain.NETWORK, NO_REGION);
    public static final OwnerToken LEGACY_SERIAL = new OwnerToken(Domain.LEGACY_SERIAL, NO_REGION);

    private static final ThreadLocal<OwnerToken> CURRENT = new ThreadLocal<>();

    public static OwnerToken forRegion(long regionId) {
        return new OwnerToken(Domain.REGION, regionId);
    }

    public static OwnerToken current() {
        OwnerToken tok = CURRENT.get();
        return tok != null ? tok : new OwnerToken(Domain.UNKNOWN, NO_REGION);
    }

    /**
     * Runs {@code work} with the given token attached to the current thread,
     * restoring the previous token on return. Nested {@link #runAs} calls are
     * supported.
     */
    public static void runAs(OwnerToken token, Runnable work) {
        OwnerToken prior = CURRENT.get();
        CURRENT.set(token);
        try {
            work.run();
        } finally {
            if (prior == null) CURRENT.remove();
            else CURRENT.set(prior);
        }
    }
}
