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
package net.multiforge.neoforge.debug;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-side {@link CustomPacketPayload} wrapper for a single raw
 * {@code multiforge:debug/v1} frame.
 *
 * <p>Deliberate duplicate of {@code
 * net.multiforge.client.DebugFramePayload} — same wire format, same
 * {@code Type.id()} (i.e. same {@link ResourceLocation}
 * {@code "multiforge:debug/v1"}). NeoForge's channel negotiation keys on
 * the {@link CustomPacketPayload.Type} id, not the Java class name, so
 * both sides interoperate cleanly. Cannot live in {@code
 * multiforge-runtime} because that module is Minecraft-independent per
 * CLAUDE.md; cannot live in {@code multiforge-client} because the fork
 * build does not depend on that module. Duplicate is the simplest fix.
 */
public record DebugFramePayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DebugFramePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.parse("multiforge:debug/v1"));

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
