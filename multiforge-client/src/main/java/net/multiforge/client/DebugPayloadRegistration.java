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

import java.io.IOException;
import java.io.UncheckedIOException;
import net.multiforge.runtime.diagnostics.wire.DebugPacketCodec;
import net.multiforge.runtime.diagnostics.wire.DebugPacketKind;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the {@code multiforge:debug/v1} custom payload channel
 * ({@code docs/design/client-debug-protocol.md} §1) on the mod event
 * bus and wires received frames into {@link DebugChannelClient}.
 *
 * <p>The channel is registered {@linkplain PayloadRegistrar#playBidirectional
 * bidirectionally} with a single opaque {@code byte[]} payload ({@link
 * DebugFramePayload}) because {@link DebugPacketCodec} already frames
 * its own kind byte + length-prefixed body inside that array (§4) --
 * NeoForge's payload-type system only needs to move the bytes, not
 * understand them. On this client, the registered handler only ever
 * fires on receipt of a server-to-client frame (HELLO /
 * REGION_SNAPSHOT / HEATMAP_UPDATE / PIN_LIST / VIOLATION_EVENT); per
 * §1 the client only ever <em>sends</em> (never receives) SUBSCRIBE, so
 * there is no ambiguity about which direction a received frame is.
 */
public final class DebugPayloadRegistration {

    private static final Logger LOGGER = LoggerFactory.getLogger(DebugPayloadRegistration.class);

    /** All four gated streams -- protocol §5. */
    private static final int SUBSCRIBE_ALL = DebugPayload.Subscribe.F_REGIONS
            | DebugPayload.Subscribe.F_HEATMAP
            | DebugPayload.Subscribe.F_PINS
            | DebugPayload.Subscribe.F_VIOLATIONS;

    private DebugPayloadRegistration() {}

    static void register(RegisterPayloadHandlersEvent event, DebugChannelClient channelClient) {
        // v1.3.13: mark the channel OPTIONAL so a stock NeoForge server
        // (or a MultiForge server that has not yet wired the server side
        // of the channel) does not reject client connections. The client
        // HUD/renderers stay inert on servers that do not advertise
        // multiforge:debug/v1 — matches the mods.toml description
        // ("stays inert if the server does not advertise
        // multiforge:debug/v1"). Without this, the client's REQUIRED
        // channel registration made every connection attempt hit
        // "Incompatible client! Please use NeoForge 1.21.1-v…-beta".
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playBidirectional(DebugFramePayload.TYPE, DebugFramePayload.STREAM_CODEC, (payload, context) -> {
            byte[] raw = payload.data();
            DebugPacketCodec.Frame frame;
            try {
                frame = DebugPacketCodec.readFrame(raw);
            } catch (IOException | IllegalArgumentException | UncheckedIOException e) {
                // Protocol §9 invariant 1 / CLAUDE.md ground rule 5: never let
                // a malformed or unrecognized frame escape into the network
                // thread -- drop it and move on.
                LOGGER.warn("Dropping malformed multiforge:debug/v1 frame ({} bytes): {}", raw.length, e.toString());
                return;
            }
            try {
                // Re-parses `raw` internally; kept this way (rather than
                // handing the already-decoded Frame to a new overload) so
                // DebugChannelClient -- unit-tested in isolation, see
                // DebugChannelClientTest -- stays untouched per Track C1
                // scope. Negligible cost at this channel's 4 Hz cadence.
                channelClient.onFrame(raw);
            } catch (IOException e) {
                LOGGER.warn("Dropping undecodable multiforge:debug/v1 {} frame: {}", frame.kind(), e.toString());
                return;
            }
            if (frame.kind() == DebugPacketKind.HELLO && !channelClient.hasSubscribed()) {
                // v1.3.16: once-per-connection latch. Pre-v1.3.16 we
                // sent a SUBSCRIBE_ALL every time a HELLO arrived, but
                // v1.3.14's HeartbeatEmitter emits HELLO at 4 Hz as a
                // keepalive, so we were flooding the server with 4
                // SUBSCRIBEs per second per client. Now we only send
                // once per connection; hasSubscribed() is reset on
                // client-side disconnect (see DebugChannelClient).
                context.reply(new DebugFramePayload(channelClient.encodeSubscribe(SUBSCRIBE_ALL)));
                channelClient.markSubscribed();
            }
        });
    }
}
