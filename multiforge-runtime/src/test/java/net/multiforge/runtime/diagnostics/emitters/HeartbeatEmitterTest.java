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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import org.junit.jupiter.api.Test;

class HeartbeatEmitterTest {

    @Test
    void emitProducesWellFormedHello() {
        List<DebugPayload> received = new ArrayList<>();
        HeartbeatEmitter emitter = new HeartbeatEmitter(1, 20, "multiforge-test-build", received::add);

        emitter.emit();

        assertThat(received).hasSize(1);
        DebugPayload.Hello hello = (DebugPayload.Hello) received.get(0);
        assertThat(hello.protocolVersion()).isEqualTo(1);
        assertThat(hello.tickHz()).isEqualTo(20);
        assertThat(hello.buildLabel()).isEqualTo("multiforge-test-build");
    }

    @Test
    void helloForNewSubscriberProducesHello() {
        List<DebugPayload> received = new ArrayList<>();
        HeartbeatEmitter emitter = new HeartbeatEmitter(1, 20, "multiforge-test-build", received::add);

        emitter.helloForNewSubscriber(new PlayerRef(UUID.randomUUID(), "steve"));

        assertThat(received).hasSize(1).first().isInstanceOf(DebugPayload.Hello.class);
    }

    @Test
    void goldenThreeEmitTicksProduceThreePayloads() {
        List<DebugPayload> received = new ArrayList<>();
        HeartbeatEmitter emitter = new HeartbeatEmitter(1, 20, "b", received::add);

        emitter.emit();
        emitter.emit();
        emitter.emit();

        assertThat(received).hasSize(3);
        assertThat(received).allSatisfy(p -> assertThat(p).isInstanceOf(DebugPayload.Hello.class));
    }
}
