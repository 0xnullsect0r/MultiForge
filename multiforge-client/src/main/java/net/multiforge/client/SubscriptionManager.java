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
package net.multiforge.client;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import net.multiforge.runtime.diagnostics.wire.DebugPacketCodec;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;

/**
 * Owns the client's {@code SUBSCRIBE} mask for one connection
 * (protocol §5).
 *
 * <p>Replaces v1.3.16's boolean "have I subscribed yet" latch. That
 * latch fixed real SUBSCRIBE spam — v1.3.15 re-sent a full mask on
 * every 4&nbsp;Hz {@code HELLO} keepalive — but it over-corrected: the
 * client could then never change its mind. Two things depend on being
 * able to:
 *
 * <ul>
 *   <li>Per-overlay config ({@link MultiForgeDebugConfig}). Turning the
 *       heatmap off should stop the server sending heat frames, not
 *       just hide them client-side.
 *   <li>Protocol §6's permission re-check, which is specified to happen
 *       "on every {@code SUBSCRIBE} frame, not cached for the life of
 *       the connection" and explicitly anticipates a client re-sending
 *       when the user toggles an overlay. With a once-per-connection
 *       latch that re-check was unreachable.
 * </ul>
 *
 * <p>So: track the last mask actually sent ({@code null} = none yet on
 * this connection) and send only when the desired mask differs. Idle
 * traffic is therefore zero, exactly as with the latch.
 *
 * <p>The config is injected as an {@link IntSupplier} rather than read
 * statically so this class is unit-testable without a loaded {@code
 * ModConfigSpec}.
 */
public final class SubscriptionManager {

    private final DebugHudState state;
    private final IntSupplier configMask;

    /** Last mask sent on this connection, or {@code null} if none. */
    private Integer lastSentMask;

    /** Server protocol version from HELLO; 0 until one arrives. */
    private int serverProtocol;

    public SubscriptionManager(DebugHudState state, IntSupplier configMask) {
        this.state = Objects.requireNonNull(state, "state");
        this.configMask = Objects.requireNonNull(configMask, "configMask");
    }

    /** Called when a HELLO is accepted, so we know what the peer speaks. */
    public void onHello(int protocolVersion) {
        this.serverProtocol = protocolVersion;
    }

    /**
     * The mask we want right now: whatever the config asks for, zeroed
     * entirely while the master F6 toggle is off, and with {@code
     * F_OWNERSHIP} stripped on a protocol-1 server that has no such
     * stream (protocol §8 — an old server ignores unknown bits, but
     * there is no reason to set one it cannot honour).
     */
    public int desiredMask() {
        if (!state.overlaysEnabled()) return 0;
        int mask = configMask.getAsInt();
        if (serverProtocol < 2) mask &= ~DebugPayload.Subscribe.F_OWNERSHIP;
        return mask;
    }

    /**
     * Send a {@code SUBSCRIBE} if the desired mask has changed since
     * the last one we sent.
     *
     * @param sender how to put a frame on the wire; separate from this
     *     class so it stays unit-testable without a NeoForge network
     *     context.
     * @return true if a frame was sent.
     */
    public boolean syncIfChanged(Consumer<byte[]> sender) {
        int desired = desiredMask();
        if (lastSentMask != null && lastSentMask == desired) {
            return false;
        }
        sender.accept(DebugPacketCodec.encodeSubscribe(new DebugPayload.Subscribe(desired)));
        lastSentMask = desired;
        return true;
    }

    /** Forget this connection's mask so the next server gets a fresh SUBSCRIBE. */
    public void reset() {
        lastSentMask = null;
        serverProtocol = 0;
    }

    /** @return the last mask sent, or {@code null} if none yet. */
    public Integer lastSentMask() {
        return lastSentMask;
    }
}
