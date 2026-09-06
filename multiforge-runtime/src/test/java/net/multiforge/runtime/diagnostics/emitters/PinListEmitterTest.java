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
package net.multiforge.runtime.diagnostics.emitters;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.pin.RegionPin;
import net.multiforge.runtime.region.pin.RegionPinManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinListEmitterTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");

    @Test
    void emitPackagesEveryPinInTheManager(@TempDir Path tmp) throws IOException {
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.toml"));
        pins.add(new RegionPin("base-alpha", OW, -2, -2, 2, 2));
        List<DebugPayload> received = new ArrayList<>();
        PinListEmitter emitter = new PinListEmitter(pins, received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit();

        assertThat(received).hasSize(1);
        DebugPayload.PinList list = (DebugPayload.PinList) received.get(0);
        assertThat(list.pins()).hasSize(1);
        DebugPayload.PinBox box = list.pins().get(0);
        assertThat(box.id()).isEqualTo("base-alpha");
        assertThat(box.worldId()).isEqualTo("minecraft:overworld");
        assertThat(box.fromChunkX()).isEqualTo(-2);
        assertThat(box.toChunkX()).isEqualTo(2);
    }

    @Test
    void emptyManagerProducesEmptyNotNullPinList(@TempDir Path tmp) {
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.toml"));
        List<DebugPayload> received = new ArrayList<>();
        PinListEmitter emitter = new PinListEmitter(pins, received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit();

        DebugPayload.PinList list = (DebugPayload.PinList) received.get(0);
        assertThat(list.pins()).isEmpty();
    }

    @Test
    void goldenThreeEmitTicksProduceThreePayloads(@TempDir Path tmp) {
        RegionPinManager pins = new RegionPinManager(tmp.resolve("pins.toml"));
        List<DebugPayload> received = new ArrayList<>();
        PinListEmitter emitter = new PinListEmitter(pins, received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit();
        emitter.emit();
        emitter.emit();

        assertThat(received).hasSize(3);
        assertThat(received).allSatisfy(p -> assertThat(p).isInstanceOf(DebugPayload.PinList.class));
    }
}
