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
package net.multiforge.runtime.scheduler;

import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.ownership.OwnershipEnforcer;
import net.multiforge.runtime.region.RegionTickBody;
import net.multiforge.runtime.telemetry.OtelExporter;

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
     * Thrown by {@link #install} when a MultiForge host is already
     * installed in {@link #CURRENT}. This is the "same-JVM re-install"
     * case — usually a dedi GameTestServer reusing one JVM across
     * successive servers without a preceding {@link #shutdown}. The
     * fork's {@code ServerLifecycleHooks} handler swallows this
     * specific subclass silently (idempotent bootstrap); every other
     * {@link IllegalStateException} propagates.
     *
     * <p>/67 round-4 fix (1.4): pre-fix the fork handler swallowed
     * every {@code IllegalStateException} the same way, masking the
     * "foreign {@code SchedulerHost} bound to {@link ServerDomains} —
     * refuse to overwrite" case. That case now propagates as a raw
     * {@code IllegalStateException} from {@link ServerDomains#install}.
     */
    public static final class AlreadyInstalledException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        AlreadyInstalledException() {
            super("A regionized runtime is already installed; call shutdown() first");
        }
    }

    /**
     * Construct a {@link MultiThreadedSchedulerHost} with the supplied
     * {@code config} and {@code body}, install it as the {@link
     * net.multiforge.api.scheduler.ServerDomains} binding, and record
     * it as the process-wide current runtime.
     *
     * @throws AlreadyInstalledException if a MultiForge runtime is
     *         already installed in {@link #CURRENT} — call {@link
     *         #shutdown} first to replace it. This is a subclass of
     *         {@link IllegalStateException} so callers that swallow
     *         "already installed" specifically can catch it without
     *         also masking the foreign-host case below.
     * @throws IllegalStateException if a non-MultiForge {@link
     *         net.multiforge.api.spi.SchedulerHost} is bound in
     *         {@link ServerDomains} — this is a hard error (usually a
     *         rogue ServiceLoader binding); the fork handler must NOT
     *         swallow it.
     */
    public static MultiThreadedSchedulerHost install(MultiForgeConfig config, RegionTickBody body) {
        MultiThreadedSchedulerHost host = new MultiThreadedSchedulerHost(config, body);
        if (!CURRENT.compareAndSet(null, host)) {
            host.close();
            throw new AlreadyInstalledException();
        }
        try {
            host.install();
        } catch (RuntimeException e) {
            // ServerDomains.install rejects if a stale HOST is bound (crash-recovery,
            // ServiceLoader-picked binding, etc.). Roll back CURRENT so we don't leave
            // a split-brain where MultiForgeRegionizedRuntime.current() returns the
            // fresh host while ServerDomains routes to the stale one.
            CURRENT.compareAndSet(host, null);
            host.close();
            throw e;
        }
        // Opt-in only: a no-op unless -Dmultiforge.otel.endpoint is set (see
        // docs/operator-handbook.md and OtelExporter's class doc).
        OtelExporter.startFromSystemProperty();
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
     *
     * <p>Also unbinds the {@link OwnershipEnforcer} tick-thread and
     * reroute-target bindings the fork's {@code ServerLifecycleHooks}
     * patch attached to this server, so the OwnershipEnforcer doesn't
     * pin a dead {@code MinecraftServer.execute} reference across the
     * server-stop / next-server-start window (matters for the dedi
     * GameTestServer which reuses one JVM across successive servers).
     *
     * <p>Uses try/finally so that unbinding still happens even if
     * {@code host.close()} throws — otherwise a stuck worker exception
     * during shutdown would leave both {@link ServerDomains#HOST} and
     * the OwnershipEnforcer bindings pointing at the dead host.
     */
    public static void shutdown() {
        MultiThreadedSchedulerHost host = CURRENT.getAndSet(null);
        try {
            if (host != null) host.close();
        } finally {
            ServerDomains.uninstall();
            OwnershipEnforcer.unbindTickThreadAndRerouteTarget();
            OtelExporter.stop();
        }
    }
}
