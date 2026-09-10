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
 * REGION_SNAPSHOT / HEATMAP_UPDATE / PIN_LIST / VIOLATION_EVENT /
 * CHUNK_OWNERSHIP); per §1 the client only ever <em>sends</em> (never
 * receives) SUBSCRIBE, so there is no ambiguity about which direction a
 * received frame is.
 */
public final class DebugPayloadRegistration {

    private static final Logger LOGGER = LoggerFactory.getLogger(DebugPayloadRegistration.class);

    private DebugPayloadRegistration() {}

    static void register(
            RegisterPayloadHandlersEvent event,
            DebugChannelClient channelClient,
            DebugHudState state,
            SubscriptionManager subscriptions) {
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
            } catch (IOException | IllegalArgumentException | UncheckedIOException e) {
                LOGGER.warn("Dropping undecodable multiforge:debug/v1 {} frame: {}", frame.kind(), e.toString());
                return;
            }
            if (frame.kind() != DebugPacketKind.HELLO) {
                return;
            }
            // v1.4.0 / protocol §8: a client MUST read
            // HELLO.protocolVersion before sending SUBSCRIBE and MUST
            // NOT subscribe to a version it does not understand.
            // Through v1.3.18 the client read the field, stored it, and
            // subscribed regardless.
            int serverProtocol = state.hello() == null ? 0 : state.hello().protocolVersion();
            if (!DebugPacketCodec.supportsProtocol(serverProtocol)) {
                if (!state.protocolUnsupported()) {
                    LOGGER.warn(
                            "multiforge:debug/v1 — server speaks protocol {}; this client supports {}..{}. Not subscribing.",
                            serverProtocol,
                            DebugPacketCodec.MIN_SUPPORTED_PROTOCOL,
                            DebugPacketCodec.PROTOCOL_VERSION);
                }
                state.markProtocolUnsupported(serverProtocol);
                return;
            }
            subscriptions.onHello(serverProtocol);
            // Only sends when the desired mask actually changed, so the
            // server's 4 Hz HELLO keepalive does not produce SUBSCRIBE
            // spam (the v1.3.15 bug that v1.3.16's latch was added for).
            subscriptions.syncIfChanged(bytes -> context.reply(new DebugFramePayload(bytes)));
        });
    }
}
