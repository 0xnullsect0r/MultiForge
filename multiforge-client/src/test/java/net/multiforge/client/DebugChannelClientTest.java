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
package net.multiforge.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import net.multiforge.runtime.diagnostics.wire.DebugPacketCodec;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import org.junit.jupiter.api.Test;

class DebugChannelClientTest {

    @Test
    void helloUpdatesState() throws IOException {
        DebugHudState state = new DebugHudState();
        DebugChannelClient client = new DebugChannelClient(state);
        client.onFrame(DebugPacketCodec.encodeHello(new DebugPayload.Hello(1, 20, "test")));
        assertThat(state.hello()).isNotNull();
        assertThat(state.hello().buildLabel()).isEqualTo("test");
    }

    @Test
    void regionSnapshotPopulatesF3Lines() throws IOException {
        DebugHudState state = new DebugHudState();
        DebugChannelClient client = new DebugChannelClient(state);
        DebugPayload.RegionSnapshot snap = new DebugPayload.RegionSnapshot(
                5,
                List.of(
                        new DebugPayload.RegionStat(1, 1, 10.0, 15.0, 42),
                        new DebugPayload.RegionStat(2, 3, 22.0, 27.5, 250)));
        client.onFrame(DebugPacketCodec.encodeRegionSnapshot(snap));
        assertThat(state.f3Lines()).hasSize(2);
        assertThat(state.f3Lines().get(1L)).contains("mspt=10.0/15.0").contains("owned=42");
        assertThat(state.f3Lines().get(2L)).contains("sections=3");
    }

    @Test
    void violationsAccumulateBoundedInReverseOrder() throws IOException {
        DebugHudState state = new DebugHudState();
        DebugChannelClient client = new DebugChannelClient(state);
        for (int i = 0; i < 250; i++) {
            client.onFrame(
                    DebugPacketCodec.encodeViolation(new DebugPayload.ViolationEvent(i, "mod", "site", "d" + i)));
        }
        List<DebugPayload.ViolationEvent> got = state.recentViolations();
        assertThat(got).hasSize(200);
        // Newest first.
        assertThat(got.get(0).epochMillis()).isEqualTo(249L);
        assertThat(got.get(199).epochMillis()).isEqualTo(50L);
    }

    @Test
    void pinListAndHeatmapReplaceLastValue() throws IOException {
        DebugHudState state = new DebugHudState();
        DebugChannelClient client = new DebugChannelClient(state);

        client.onFrame(DebugPacketCodec.encodePinList(
                new DebugPayload.PinList(List.of(new DebugPayload.PinBox("a", "minecraft:overworld", 0, 0, 1, 1)))));
        assertThat(state.pins())
                .hasSize(1)
                .first()
                .extracting(DebugPayload.PinBox::id)
                .isEqualTo("a");

        client.onFrame(DebugPacketCodec.encodePinList(new DebugPayload.PinList(List.of())));
        assertThat(state.pins()).isEmpty();

        client.onFrame(DebugPacketCodec.encodeHeatmap(new DebugPayload.HeatmapUpdate(
                "minecraft:overworld", List.of(new DebugPayload.ChunkHeat(0, 0, 3.14f)))));
        assertThat(state.latestHeatmap().heats()).hasSize(1);
        assertThat(state.latestHeatmap().heats().get(0).heatMspt()).isEqualTo(3.14f);
    }

    @Test
    void encodesSubscribeFlagsForReturnTrip() throws IOException {
        DebugHudState state = new DebugHudState();
        DebugChannelClient client = new DebugChannelClient(state);
        byte[] framed = client.encodeSubscribe(DebugPayload.Subscribe.F_REGIONS | DebugPayload.Subscribe.F_VIOLATIONS);
        DebugPacketCodec.Frame frame = DebugPacketCodec.readFrame(framed);
        DebugPayload.Subscribe s = DebugPacketCodec.decodeSubscribe(frame.body());
        assertThat(s.wants(DebugPayload.Subscribe.F_REGIONS)).isTrue();
        assertThat(s.wants(DebugPayload.Subscribe.F_VIOLATIONS)).isTrue();
        assertThat(s.wants(DebugPayload.Subscribe.F_HEATMAP)).isFalse();
    }
}
