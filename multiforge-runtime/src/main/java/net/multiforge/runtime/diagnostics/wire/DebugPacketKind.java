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
package net.multiforge.runtime.diagnostics.wire;

/**
 * The message types carried on the {@code multiforge:debug/v1} custom
 * payload channel between the MultiForge server and the client debug
 * mod. Each is serialized to a discriminated byte stream by
 * {@link DebugPacketCodec}.
 *
 * <ul>
 *   <li>{@link #HELLO} — server → client on handshake, advertises the
 *       protocol version and the server's tick rate.</li>
 *   <li>{@link #REGION_SNAPSHOT} — server → client, periodic snapshot
 *       of every live region's id, section count, MSPT, and owned
 *       entity count. Drives the F3 overlay + chunk-border colouring.</li>
 *   <li>{@link #HEATMAP_UPDATE} — server → client, per-chunk MSPT heat
 *       for the client's view radius. Powers the tick-cost heatmap.</li>
 *   <li>{@link #PIN_LIST} — server → client, snapshot of every
 *       operator-created region pin so the client can draw selection
 *       boxes.</li>
 *   <li>{@link #VIOLATION_EVENT} — server → client, one line for the
 *       live violation/hop side panel.</li>
 *   <li>{@link #SUBSCRIBE} — client → server, adjust which streams the
 *       client wants pushed to it.</li>
 * </ul>
 */
public enum DebugPacketKind {
    HELLO(0x01),
    REGION_SNAPSHOT(0x02),
    HEATMAP_UPDATE(0x03),
    PIN_LIST(0x04),
    VIOLATION_EVENT(0x05),
    SUBSCRIBE(0x10);

    private final int wireId;

    DebugPacketKind(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static DebugPacketKind fromWireId(int id) {
        for (DebugPacketKind k : values()) if (k.wireId == id) return k;
        throw new IllegalArgumentException("Unknown debug packet kind: " + id);
    }
}
