/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.client;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * NeoForge {@link CustomPacketPayload} wrapper for a single raw {@code
 * multiforge:debug/v1} frame.
 *
 * <p>The wire format inside {@link #data()} is entirely owned by {@link
 * net.multiforge.runtime.diagnostics.wire.DebugPacketCodec} -- kind byte
 * + length-prefixed body, see {@code docs/design/client-debug-protocol.md}
 * §4. This record exists only to satisfy NeoForge's payload-channel
 * registration API ({@link DebugPayloadRegistration}); it does not
 * itself interpret the bytes.
 *
 * <p>Note: the generated record {@code equals}/{@code hashCode} use
 * reference identity for the {@code byte[]} component (arrays do not
 * override {@code equals}). That is fine here -- payload instances are
 * never compared for equality, only encoded/decoded.
 */
public record DebugFramePayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DebugFramePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.parse(DebugChannelClient.CHANNEL_ID));

    public static final StreamCodec<ByteBuf, DebugFramePayload> STREAM_CODEC =
            ByteBufCodecs.BYTE_ARRAY.map(DebugFramePayload::new, DebugFramePayload::data);

    public DebugFramePayload {
        Objects.requireNonNull(data, "data");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
