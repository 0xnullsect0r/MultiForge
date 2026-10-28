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
package net.multiforge.runtime.diagnostics.emitters;

import java.util.Objects;
import java.util.function.Consumer;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.jetbrains.annotations.ApiStatus;

/**
 * Produces {@code VIOLATION_EVENT} payloads (Track C1 task 1.9),
 * event-driven off {@link ViolationLogger#subscribe}.
 *
 * <p>Per {@code docs/design/client-debug-protocol.md} §3/§7.5/§9
 * invariant 10, exactly one frame is produced per emitted WARN log
 * line — {@link ViolationLogger#subscribe} already enforces that
 * fan-out discipline (it fires the same rate-limiter-gated branches
 * that write to the log), so this emitter does no additional
 * throttling of its own; it is a pure translation from {@link
 * ViolationLogger.ViolationEvent} to the wire {@link
 * DebugPayload.ViolationEvent}.
 */
@ApiStatus.Internal
public final class ViolationEmitter {

    private final Consumer<DebugPayload> sink;
    private final PermissionFilter permissionFilter;

    public ViolationEmitter(Consumer<DebugPayload> sink, PermissionFilter permissionFilter) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.permissionFilter = Objects.requireNonNull(permissionFilter, "permissionFilter");
    }

    /**
     * Package {@code event} into a wire {@code VIOLATION_EVENT} and
     * sink it. {@code modId == null} (the site-scoped {@link
     * ViolationLogger#warn(String, String)} overload) is substituted
     * with {@code ""} — the wire format has no null representation
     * (§7.5).
     */
    public void emit(ViolationLogger.ViolationEvent event) {
        Objects.requireNonNull(event, "event");
        DebugPayload.ViolationEvent payload = new DebugPayload.ViolationEvent(
                event.epochMillis(), event.modId() == null ? "" : event.modId(), event.site(), event.detail());
        if (permissionFilter.canSee(payload, PlayerRef.ANY)) {
            sink.accept(payload);
        }
    }

    /**
     * Subscribe a {@link ViolationEmitter} to {@link ViolationLogger}.
     *
     * <p>A violation is logged on whatever thread caused it, often a region
     * worker. The frame is built and sent on the host's diagnostics thread
     * ({@link MultiThreadedSchedulerHost#runDiagnostics}), so no region worker
     * encodes or sends debug traffic (protocol §3).
     *
     * @param host supplies the diagnostics thread the frames are sent from
     * @return handle that unsubscribes from {@link ViolationLogger}
     */
    public static AutoCloseable install(
            MultiThreadedSchedulerHost host, Consumer<DebugPayload> sink, PermissionFilter permissionFilter) {
        Objects.requireNonNull(host, "host");
        ViolationEmitter emitter = new ViolationEmitter(sink, permissionFilter);
        return ViolationLogger.subscribe(event -> host.runDiagnostics(() -> emitter.emit(event)));
    }
}
