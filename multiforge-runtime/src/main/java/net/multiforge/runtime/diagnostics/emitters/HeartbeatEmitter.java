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
package net.multiforge.runtime.diagnostics.emitters;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import net.multiforge.runtime.diagnostics.wire.DebugPacketCodec;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Produces {@link DebugPacketCodec} {@code HELLO} frames (Track C1
 * task 1.7).
 *
 * <p>Per {@code docs/design/client-debug-protocol.md} §3/§6, {@code
 * HELLO} is <em>unconditional</em> and sent exactly once per
 * connection — not gated by permission or subscription. That
 * per-connection send is a network-layer event this MC-free module
 * cannot observe directly, so it is exposed as {@link
 * #helloForNewSubscriber(PlayerRef)}: the fork's channel-open hook
 * calls it once when a player's {@code multiforge:debug/v1} channel
 * opens.
 *
 * <p>{@link #emit()} is the periodic half of this emitter's job: a
 * cheap, small keepalive re-broadcast of the same {@code HELLO}
 * payload on the shared 4&nbsp;Hz diagnostics tick (installed via
 * {@link #install}), so a client-side liveness check has something to
 * key off without waiting on a fresh connection. This is a
 * deliberately conservative choice given no other keepalive-shaped
 * packet kind exists in the frozen protocol (§2) — the fork's {@code
 * PayloadDistributor} is free to suppress the keepalive down
 * already-{@code HELLO}'d connections if a future protocol revision
 * adds a dedicated heartbeat kind.
 */
public final class HeartbeatEmitter {

    /** Matches the other 4Hz streams (docs/design/client-debug-protocol.md §3). */
    public static final long PERIOD_MILLIS = 250L;

    private final int protocolVersion;
    private final int tickHz;
    private final String buildLabel;
    private final Consumer<DebugPayload> sink;

    public HeartbeatEmitter(int protocolVersion, int tickHz, String buildLabel, Consumer<DebugPayload> sink) {
        this.protocolVersion = protocolVersion;
        this.tickHz = tickHz;
        this.buildLabel = Objects.requireNonNull(buildLabel, "buildLabel");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Periodic 4Hz keepalive — always produces exactly one payload per call. */
    public void emit() {
        sink.accept(hello());
    }

    /**
     * Fork-side hook: call once when {@code subscriber}'s {@code
     * multiforge:debug/v1} channel opens (per §3, unconditional — do
     * not gate this call itself on permission or subscription state;
     * those gates apply to the four push streams, not {@code HELLO}).
     */
    public void helloForNewSubscriber(PlayerRef subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        sink.accept(hello());
    }

    private DebugPayload.Hello hello() {
        return new DebugPayload.Hello(protocolVersion, tickHz, buildLabel);
    }

    /**
     * Wire a {@link HeartbeatEmitter} into {@code host}'s shared
     * diagnostics scheduler at 4&nbsp;Hz, using {@link
     * DebugPacketCodec#PROTOCOL_VERSION} and {@code tickHz = 20}
     * (Vanilla parity default).
     *
     * @param permissionFilter accepted for API symmetry with the other
     *     emitters' {@code install} entry points; unused here since
     *     {@code HELLO} is unconditional (see class javadoc).
     * @return handle to stop the periodic keepalive; does not affect
     *     {@link #helloForNewSubscriber}, which the caller invokes
     *     directly.
     */
    public static AutoCloseable install(
            MultiThreadedSchedulerHost host,
            String buildLabel,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(permissionFilter, "permissionFilter");
        HeartbeatEmitter emitter = new HeartbeatEmitter(DebugPacketCodec.PROTOCOL_VERSION, 20, buildLabel, sink);
        ScheduledFuture<?> future = host.scheduleGlobal(emitter::emit, PERIOD_MILLIS);
        return () -> future.cancel(false);
    }
}
