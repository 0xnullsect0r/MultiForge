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
package net.multiforge.runtime.diagnostics.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Length-prefixed big-endian encoder/decoder for the debug payload
 * records. Frame layout:
 *
 * <pre>
 *   int8   packetKindWireId
 *   int32  bodyLength
 *   byte[] body (kind-specific)
 * </pre>
 *
 * <p>Bounded by {@link #MAX_FRAME_BYTES} so a malicious client
 * cannot allocate arbitrary memory. Strings use UTF-8 with an int16
 * length prefix.
 */
public final class DebugPacketCodec {

    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_FRAME_BYTES = 1 << 20; // 1 MiB

    private DebugPacketCodec() {}

    public static byte[] encodeHello(DebugPayload.Hello hello) {
        return frame(DebugPacketKind.HELLO, out -> {
            out.writeInt(hello.protocolVersion());
            out.writeInt(hello.tickHz());
            writeString(out, hello.buildLabel());
        });
    }

    public static DebugPayload.Hello decodeHello(byte[] body) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            int ver = in.readInt();
            int hz = in.readInt();
            String label = readString(in);
            return new DebugPayload.Hello(ver, hz, label);
        }
    }

    public static byte[] encodeRegionSnapshot(DebugPayload.RegionSnapshot s) {
        return frame(DebugPacketKind.REGION_SNAPSHOT, out -> {
            out.writeLong(s.tick());
            out.writeInt(s.regions().size());
            for (DebugPayload.RegionStat r : s.regions()) {
                out.writeLong(r.regionId());
                out.writeInt(r.sectionCount());
                out.writeDouble(r.msptP50());
                out.writeDouble(r.msptP95());
                out.writeInt(r.ownedEntities());
            }
        });
    }

    public static DebugPayload.RegionSnapshot decodeRegionSnapshot(byte[] body) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            long tick = in.readLong();
            int n = in.readInt();
            requireLen(n, 65536, "region count");
            List<DebugPayload.RegionStat> regs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                regs.add(new DebugPayload.RegionStat(
                        in.readLong(), in.readInt(), in.readDouble(), in.readDouble(), in.readInt()));
            }
            return new DebugPayload.RegionSnapshot(tick, regs);
        }
    }

    public static byte[] encodeHeatmap(DebugPayload.HeatmapUpdate u) {
        return frame(DebugPacketKind.HEATMAP_UPDATE, out -> {
            writeString(out, u.worldId());
            out.writeInt(u.heats().size());
            for (DebugPayload.ChunkHeat h : u.heats()) {
                out.writeInt(h.chunkX());
                out.writeInt(h.chunkZ());
                out.writeFloat(h.heatMspt());
            }
        });
    }

    public static DebugPayload.HeatmapUpdate decodeHeatmap(byte[] body) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            String world = readString(in);
            int n = in.readInt();
            requireLen(n, 1 << 16, "heat count");
            List<DebugPayload.ChunkHeat> hs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                hs.add(new DebugPayload.ChunkHeat(in.readInt(), in.readInt(), in.readFloat()));
            }
            return new DebugPayload.HeatmapUpdate(world, hs);
        }
    }

    public static byte[] encodePinList(DebugPayload.PinList list) {
        return frame(DebugPacketKind.PIN_LIST, out -> {
            out.writeInt(list.pins().size());
            for (DebugPayload.PinBox p : list.pins()) {
                writeString(out, p.id());
                writeString(out, p.worldId());
                out.writeInt(p.fromChunkX());
                out.writeInt(p.fromChunkZ());
                out.writeInt(p.toChunkX());
                out.writeInt(p.toChunkZ());
            }
        });
    }

    public static DebugPayload.PinList decodePinList(byte[] body) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            int n = in.readInt();
            requireLen(n, 1 << 12, "pin count");
            List<DebugPayload.PinBox> ps = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String id = readString(in);
                String world = readString(in);
                ps.add(new DebugPayload.PinBox(id, world, in.readInt(), in.readInt(), in.readInt(), in.readInt()));
            }
            return new DebugPayload.PinList(ps);
        }
    }

    public static byte[] encodeViolation(DebugPayload.ViolationEvent e) {
        return frame(DebugPacketKind.VIOLATION_EVENT, out -> {
            out.writeLong(e.epochMillis());
            writeString(out, e.modId());
            writeString(out, e.site());
            writeString(out, e.detail());
        });
    }

    public static DebugPayload.ViolationEvent decodeViolation(byte[] body) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            long ts = in.readLong();
            String mod = readString(in);
            String site = readString(in);
            String detail = readString(in);
            return new DebugPayload.ViolationEvent(ts, mod, site, detail);
        }
    }

    public static byte[] encodeSubscribe(DebugPayload.Subscribe s) {
        return frame(DebugPacketKind.SUBSCRIBE, out -> out.writeInt(s.flags()));
    }

    public static DebugPayload.Subscribe decodeSubscribe(byte[] body) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            return new DebugPayload.Subscribe(in.readInt());
        }
    }

    /** Read the kind + body from a raw frame. */
    public static Frame readFrame(byte[] raw) throws IOException {
        if (raw == null || raw.length < 5) throw new IOException("frame too short");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        DebugPacketKind kind = DebugPacketKind.fromWireId(in.readByte() & 0xFF);
        int bodyLen = in.readInt();
        if (bodyLen < 0 || bodyLen > MAX_FRAME_BYTES)
            throw new IOException("frame body length out of range: " + bodyLen);
        if (bodyLen != raw.length - 5) throw new IOException("frame body length mismatch");
        byte[] body = new byte[bodyLen];
        in.readFully(body);
        return new Frame(kind, body);
    }

    public record Frame(DebugPacketKind kind, byte[] body) {}

    // ---- helpers --------------------------------------------------------

    @FunctionalInterface
    private interface WriteBody {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] frame(DebugPacketKind kind, WriteBody body) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            body.write(out);
            byte[] payload = buf.toByteArray();

            ByteArrayOutputStream framed = new ByteArrayOutputStream(payload.length + 5);
            DataOutputStream fout = new DataOutputStream(framed);
            fout.writeByte(kind.wireId());
            fout.writeInt(payload.length);
            fout.write(payload);
            return framed.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] utf = s.getBytes(StandardCharsets.UTF_8);
        if (utf.length > 65535) throw new IOException("string too long: " + utf.length);
        out.writeShort(utf.length);
        out.write(utf);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void requireLen(int n, int max, String label) throws IOException {
        if (n < 0 || n > max) throw new IOException(label + " out of range: " + n);
    }
}
