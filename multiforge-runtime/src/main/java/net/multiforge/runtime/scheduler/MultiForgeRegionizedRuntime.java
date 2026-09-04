/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.scheduler;

import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.region.RegionTickBody;

/**
 * Process-wide singleton holder for the live {@link
 * MultiThreadedSchedulerHost}. The M8 fork patches — hand-written glue
 * under {@code upstream/neoforge-1.21.1/src/main/java/net/multiforge/} —
 * call {@link #install} from an early server-lifecycle hook, then
 * retrieve the host via {@link #current} from patched call sites.
 *
 * <p>Kept as a thin, focused holder so the fork's glue never needs to
 * know how a {@link MultiThreadedSchedulerHost} is constructed or how
 * to configure its worker pool.
 *
 * <p>Not thread-safe against concurrent {@link #install} calls; the
 * intended pattern is one call per JVM lifetime from the server's own
 * bootstrap thread before any region worker starts.
 */
public final class MultiForgeRegionizedRuntime {

    private static final AtomicReference<MultiThreadedSchedulerHost> CURRENT = new AtomicReference<>();

    private MultiForgeRegionizedRuntime() {}

    /**
     * Construct a {@link MultiThreadedSchedulerHost} with the supplied
     * {@code config} and {@code body}, install it as the {@link
     * net.multiforge.api.scheduler.ServerDomains} binding, and record
     * it as the process-wide current runtime.
     *
     * @throws IllegalStateException if a runtime is already installed —
     *         call {@link #shutdown} first to replace it.
     */
    public static MultiThreadedSchedulerHost install(MultiForgeConfig config, RegionTickBody body) {
        MultiThreadedSchedulerHost host = new MultiThreadedSchedulerHost(config, body);
        if (!CURRENT.compareAndSet(null, host)) {
            host.close();
            throw new IllegalStateException("A regionized runtime is already installed; call shutdown() first");
        }
        host.install();
        return host;
    }

    /**
     * @return the currently-installed host, or {@code null} if no
     *         {@link #install} call has landed yet.
     */
    public static MultiThreadedSchedulerHost current() {
        return CURRENT.get();
    }

    /**
     * Close the currently-installed host, clear the holder, and unbind
     * the {@link ServerDomains} host reference so a future {@link
     * #install} succeeds cleanly. Safe to call more than once; the
     * second call is a no-op.
     */
    public static void shutdown() {
        MultiThreadedSchedulerHost host = CURRENT.getAndSet(null);
        if (host != null) host.close();
        // Unbind the API-side host reference too — otherwise subsequent
        // ServerDomains.region(...) calls route to a closed host and
        // ServerDomains.install(...) refuses to replace it.
        ServerDomains.uninstall();
    }
}
