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
import java.util.Objects;
import net.multiforge.runtime.diagnostics.wire.DebugPacketCodec;
import net.multiforge.runtime.diagnostics.wire.DebugPacketKind;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;

/**
 * Client-side glue that turns raw {@code multiforge:debug/v1} frame
 * bytes into {@link DebugPayload} records applied to a
 * {@link DebugHudState}.
 *
 * <p>Extracted here (pure Java, no NeoForge deps) so we can unit-test
 * the parse pipeline without the client runtime. The M6 patch wires
 * {@code IPayloadHandler} on the NeoForge client bus to call
 * {@link #onFrame(byte[])}.
 */
public final class DebugChannelClient {

    public static final String CHANNEL_ID = "multiforge:debug/v1";

    private final DebugHudState state;

    public DebugChannelClient(DebugHudState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public void onFrame(byte[] raw) throws IOException {
        DebugPacketCodec.Frame frame = DebugPacketCodec.readFrame(raw);
        DebugPacketKind kind = frame.kind();
        byte[] body = frame.body();
        switch (kind) {
            case HELLO -> state.apply(DebugPacketCodec.decodeHello(body));
            case REGION_SNAPSHOT -> state.apply(DebugPacketCodec.decodeRegionSnapshot(body));
            case HEATMAP_UPDATE -> state.apply(DebugPacketCodec.decodeHeatmap(body));
            case PIN_LIST -> state.apply(DebugPacketCodec.decodePinList(body));
            case VIOLATION_EVENT -> state.apply(DebugPacketCodec.decodeViolation(body));
            case SUBSCRIBE -> {
                // Server never sends SUBSCRIBE to a client — ignore politely
                // rather than throwing on stale/misdirected traffic.
            }
        }
    }

    public byte[] encodeSubscribe(int flags) {
        return DebugPacketCodec.encodeSubscribe(new DebugPayload.Subscribe(flags));
    }
}
