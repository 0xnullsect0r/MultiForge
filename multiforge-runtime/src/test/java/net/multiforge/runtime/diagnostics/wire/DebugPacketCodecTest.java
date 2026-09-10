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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DebugPacketCodecTest {

    @Test
    void helloRoundTrip() throws IOException {
        DebugPayload.Hello h = new DebugPayload.Hello(1, 20, "multiforge-1.0.0-M6");
        byte[] framed = DebugPacketCodec.encodeHello(h);
        DebugPacketCodec.Frame f = DebugPacketCodec.readFrame(framed);
        assertThat(f.kind()).isEqualTo(DebugPacketKind.HELLO);
        DebugPayload.Hello back = DebugPacketCodec.decodeHello(f.body());
        assertThat(back).isEqualTo(h);
    }

    @Test
    void regionSnapshotRoundTrip() throws IOException {
        DebugPayload.RegionSnapshot s =
                new DebugPayload.RegionSnapshot(42L, List.of(new DebugPayload.RegionStat(1, 3, 12.5, 18.9, 250)));
        byte[] framed = DebugPacketCodec.encodeRegionSnapshot(s);
        DebugPayload.RegionSnapshot back = DebugPacketCodec.decodeRegionSnapshot(
                DebugPacketCodec.readFrame(framed).body());
        assertThat(back).isEqualTo(s);
    }

    @Test
    void heatmapRoundTrip() throws IOException {
        DebugPayload.HeatmapUpdate u = new DebugPayload.HeatmapUpdate(
                "minecraft:overworld",
                List.of(new DebugPayload.ChunkHeat(0, 0, 2.5f), new DebugPayload.ChunkHeat(1, 1, 5.5f)));
        byte[] framed = DebugPacketCodec.encodeHeatmap(u);
        assertThat(DebugPacketCodec.decodeHeatmap(
                        DebugPacketCodec.readFrame(framed).body()))
                .isEqualTo(u);
    }

    @Test
    void pinListRoundTrip() throws IOException {
        DebugPayload.PinList l = new DebugPayload.PinList(
                List.of(new DebugPayload.PinBox("base-alpha", "minecraft:overworld", -1, -1, 1, 1)));
        byte[] framed = DebugPacketCodec.encodePinList(l);
        assertThat(DebugPacketCodec.decodePinList(
                        DebugPacketCodec.readFrame(framed).body()))
                .isEqualTo(l);
    }

    @Test
    void violationRoundTrip() throws IOException {
        DebugPayload.ViolationEvent e = new DebugPayload.ViolationEvent(
                1725450000000L, "kubejs", "ServerLevel.setBlock", "off-region write, auto-rerouted");
        byte[] framed = DebugPacketCodec.encodeViolation(e);
        assertThat(DebugPacketCodec.decodeViolation(
                        DebugPacketCodec.readFrame(framed).body()))
                .isEqualTo(e);
    }

    @Test
    void subscribeRoundTrip() throws IOException {
        DebugPayload.Subscribe s =
                new DebugPayload.Subscribe(DebugPayload.Subscribe.F_REGIONS | DebugPayload.Subscribe.F_HEATMAP);
        byte[] framed = DebugPacketCodec.encodeSubscribe(s);
        DebugPayload.Subscribe back = DebugPacketCodec.decodeSubscribe(
                DebugPacketCodec.readFrame(framed).body());
        assertThat(back).isEqualTo(s);
        assertThat(back.wants(DebugPayload.Subscribe.F_REGIONS)).isTrue();
        assertThat(back.wants(DebugPayload.Subscribe.F_VIOLATIONS)).isFalse();
    }

    @Test
    void frameTooShortThrows() {
        assertThatThrownBy(() -> DebugPacketCodec.readFrame(new byte[3])).isInstanceOf(IOException.class);
    }

    @Test
    void frameBodyLengthMismatchThrows() {
        // kind=HELLO, bodyLen=100, actual body 0 bytes → mismatch
        byte[] bad = {(byte) DebugPacketKind.HELLO.wireId(), 0, 0, 0, 100};
        assertThatThrownBy(() -> DebugPacketCodec.readFrame(bad)).isInstanceOf(IOException.class);
    }

    @Test
    void frameKindOutOfRangeThrows() {
        byte[] bad = {(byte) 0xEE, 0, 0, 0, 0};
        assertThatThrownBy(() -> DebugPacketCodec.readFrame(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownershipRoundTrip() throws IOException {
        DebugPayload.OwnershipUpdate u = new DebugPayload.OwnershipUpdate(
                "minecraft:overworld",
                3,
                List.of(new DebugPayload.SectionOwner(0, 0, 7L), new DebugPayload.SectionOwner(-8, 16, 9L)));
        byte[] framed = DebugPacketCodec.encodeOwnership(u);
        DebugPacketCodec.Frame f = DebugPacketCodec.readFrame(framed);
        assertThat(f.kind()).isEqualTo(DebugPacketKind.CHUNK_OWNERSHIP);
        assertThat(DebugPacketCodec.decodeOwnership(f.body())).isEqualTo(u);
    }

    @Test
    void ownershipWithNoOwnersRoundTrips() throws IOException {
        DebugPayload.OwnershipUpdate u = new DebugPayload.OwnershipUpdate("minecraft:the_nether", 0, List.of());
        DebugPayload.OwnershipUpdate back = DebugPacketCodec.decodeOwnership(
                DebugPacketCodec.readFrame(DebugPacketCodec.encodeOwnership(u)).body());
        assertThat(back).isEqualTo(u);
    }

    @Test
    void ownershipOwnerCountCeilingRejected() {
        // worldId "" (2 bytes) + shift + count, count above requireLen's 1<<16 ceiling.
        byte[] body = new byte[10];
        body[0] = 0;
        body[1] = 0; // zero-length world id
        body[5] = 0; // shift = 0 (bytes 2..5)
        body[6] = 0x00;
        body[7] = 0x02;
        body[8] = 0x00;
        body[9] = 0x01; // count = 0x00020001 > 65536
        assertThatThrownBy(() -> DebugPacketCodec.decodeOwnership(body)).isInstanceOf(IOException.class);
    }

    @Test
    void ownershipRejectsOutOfRangeShift() throws IOException {
        DebugPayload.OwnershipUpdate u = new DebugPayload.OwnershipUpdate("w", 4, List.of());
        byte[] framed = DebugPacketCodec.encodeOwnership(u);
        // Overwrite the shift field with something absurd. Layout is
        // 5-byte header, then int16 string length + 1 byte of "w", then
        // the int32 shift.
        int shiftOffset = 5 + 2 + 1;
        framed[shiftOffset] = 0x7F;
        byte[] body = DebugPacketCodec.readFrame(framed).body();
        assertThatThrownBy(() -> DebugPacketCodec.decodeOwnership(body)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void protocolVersionSupportWindow() {
        assertThat(DebugPacketCodec.supportsProtocol(DebugPacketCodec.PROTOCOL_VERSION))
                .isTrue();
        assertThat(DebugPacketCodec.supportsProtocol(DebugPacketCodec.MIN_SUPPORTED_PROTOCOL))
                .isTrue();
        assertThat(DebugPacketCodec.supportsProtocol(DebugPacketCodec.PROTOCOL_VERSION + 1))
                .isFalse();
        assertThat(DebugPacketCodec.supportsProtocol(DebugPacketCodec.MIN_SUPPORTED_PROTOCOL - 1))
                .isFalse();
    }

    @Test
    void subscribeAllCoversEveryDefinedStream() {
        int all = DebugPayload.Subscribe.F_ALL;
        assertThat(all & DebugPayload.Subscribe.F_REGIONS).isNotZero();
        assertThat(all & DebugPayload.Subscribe.F_HEATMAP).isNotZero();
        assertThat(all & DebugPayload.Subscribe.F_PINS).isNotZero();
        assertThat(all & DebugPayload.Subscribe.F_VIOLATIONS).isNotZero();
        assertThat(all & DebugPayload.Subscribe.F_OWNERSHIP).isNotZero();
    }
}
