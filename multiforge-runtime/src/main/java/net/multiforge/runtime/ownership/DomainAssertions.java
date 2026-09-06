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

import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Assertion sites patched into Vanilla/NeoForge to catch cross-domain
 * mutation. Off by default; enabled per-JVM via {@code
 * -Dmultiforge.assert=on} for CI/dev builds. In production the assertion
 * layer stays off and the runtime relies on the region-owner check plus
 * auto-reroute inside the tick pipeline.
 *
 * <p>An assertion never throws in production mode. It bumps a probe
 * counter and, when the WARN threshold fires, logs a rate-limited message.
 * The M2 tick pipeline replaces the "warn only" behaviour with the
 * auto-reroute path.
 */
public final class DomainAssertions {

    private static final String ENABLED_PROP = "multiforge.assert";
    private static final boolean ENABLED = "on".equalsIgnoreCase(System.getProperty(ENABLED_PROP, "off"));

    private DomainAssertions() {}

    /**
     * @return {@code true} if assertions are enabled; hot paths gate on
     *         this and skip the probe call entirely when off.
     */
    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Assert that the current thread's domain is {@link Domain#GLOBAL}.
     *
     * @param site short symbolic id of the assertion site, e.g. {@code
     *             "ServerLevel.setBlock"}. Used as the probe key and in
     *             log messages.
     */
    public static void assertGlobal(String site) {
        if (!ENABLED) return;
        OwnerToken tok = OwnerToken.current();
        if (tok.domain() != Domain.GLOBAL) {
            ProbeRegistry.bump(site + ":not-global");
            ViolationLogger.warn(site, "expected GLOBAL, was " + tok.domain());
        }
    }

    /**
     * Assert that the current thread owns the given region.
     *
     * @param site short symbolic id of the assertion site.
     * @param regionId the region the caller intends to mutate.
     */
    public static void assertRegion(String site, long regionId) {
        if (!ENABLED) return;
        OwnerToken tok = OwnerToken.current();
        if (tok.domain() != Domain.REGION || tok.regionId() != regionId) {
            ProbeRegistry.bump(site + ":wrong-region");
            ViolationLogger.warn(site, "expected REGION#" + regionId + ", was " + tok.domain() + "#" + tok.regionId());
        }
    }

    /**
     * Assert that the current thread is a region worker (of any region) or
     * the global thread. Used at read-mostly sites where cross-region reads
     * are permitted but async/legacy reads are suspect.
     */
    public static void assertTickThread(String site) {
        if (!ENABLED) return;
        OwnerToken tok = OwnerToken.current();
        Domain d = tok.domain();
        if (d != Domain.REGION && d != Domain.GLOBAL) {
            ProbeRegistry.bump(site + ":not-tick-thread");
            ViolationLogger.warn(site, "expected REGION or GLOBAL, was " + d);
        }
    }
}
