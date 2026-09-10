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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.diagnostics.wire.DebugPacketCodec;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import org.junit.jupiter.api.Test;

class SubscriptionManagerTest {

    /** Captures frames a manager would put on the wire. */
    private static final class Wire {
        final List<byte[]> sent = new ArrayList<>();

        void accept(byte[] frame) {
            sent.add(frame);
        }

        int lastMask() throws IOException {
            byte[] last = sent.get(sent.size() - 1);
            return DebugPacketCodec.decodeSubscribe(
                            DebugPacketCodec.readFrame(last).body())
                    .flags();
        }
    }

    @Test
    void firstSyncAlwaysSends() throws IOException {
        DebugHudState state = new DebugHudState();
        SubscriptionManager m = new SubscriptionManager(state, () -> DebugPayload.Subscribe.F_REGIONS);
        m.onHello(2);
        Wire wire = new Wire();

        assertThat(m.syncIfChanged(wire::accept)).isTrue();
        assertThat(wire.sent).hasSize(1);
        assertThat(wire.lastMask()).isEqualTo(DebugPayload.Subscribe.F_REGIONS);
    }

    @Test
    void repeatSyncWithNoChangeSendsNothing() {
        DebugHudState state = new DebugHudState();
        SubscriptionManager m = new SubscriptionManager(state, () -> DebugPayload.Subscribe.F_REGIONS);
        m.onHello(2);
        Wire wire = new Wire();

        m.syncIfChanged(wire::accept);
        // This is what stops the server's 4 Hz HELLO keepalive from
        // provoking SUBSCRIBE spam (the v1.3.15 bug).
        assertThat(m.syncIfChanged(wire::accept)).isFalse();
        assertThat(m.syncIfChanged(wire::accept)).isFalse();
        assertThat(wire.sent).hasSize(1);
    }

    @Test
    void changingTheConfigMaskResends() throws IOException {
        DebugHudState state = new DebugHudState();
        int[] configured = {DebugPayload.Subscribe.F_REGIONS};
        SubscriptionManager m = new SubscriptionManager(state, () -> configured[0]);
        m.onHello(2);
        Wire wire = new Wire();

        m.syncIfChanged(wire::accept);
        configured[0] = DebugPayload.Subscribe.F_REGIONS | DebugPayload.Subscribe.F_HEATMAP;

        assertThat(m.syncIfChanged(wire::accept)).isTrue();
        assertThat(wire.sent).hasSize(2);
        assertThat(wire.lastMask()).isEqualTo(DebugPayload.Subscribe.F_REGIONS | DebugPayload.Subscribe.F_HEATMAP);
    }

    @Test
    void masterToggleOffZeroesTheMask() throws IOException {
        DebugHudState state = new DebugHudState();
        SubscriptionManager m = new SubscriptionManager(state, () -> DebugPayload.Subscribe.F_ALL);
        m.onHello(2);
        Wire wire = new Wire();
        m.syncIfChanged(wire::accept);

        state.toggleOverlays(); // F6 → off

        assertThat(m.syncIfChanged(wire::accept)).isTrue();
        assertThat(wire.lastMask()).isZero();
        // Toggling back restores the full mask, which is also what gives
        // protocol §6's permission re-check something to fire on.
        state.toggleOverlays();
        assertThat(m.syncIfChanged(wire::accept)).isTrue();
        assertThat(wire.lastMask()).isEqualTo(DebugPayload.Subscribe.F_ALL);
    }

    @Test
    void ownershipBitIsStrippedOnAProtocolOneServer() throws IOException {
        DebugHudState state = new DebugHudState();
        SubscriptionManager m = new SubscriptionManager(state, () -> DebugPayload.Subscribe.F_ALL);
        m.onHello(1); // a v1.3.x server
        Wire wire = new Wire();

        m.syncIfChanged(wire::accept);
        int mask = wire.lastMask();
        assertThat(mask & DebugPayload.Subscribe.F_OWNERSHIP).isZero();
        assertThat(mask & DebugPayload.Subscribe.F_REGIONS).isNotZero();
        assertThat(mask & DebugPayload.Subscribe.F_HEATMAP).isNotZero();
    }

    @Test
    void ownershipBitSurvivesOnAProtocolTwoServer() throws IOException {
        DebugHudState state = new DebugHudState();
        SubscriptionManager m = new SubscriptionManager(state, () -> DebugPayload.Subscribe.F_ALL);
        m.onHello(2);
        Wire wire = new Wire();

        m.syncIfChanged(wire::accept);
        assertThat(wire.lastMask() & DebugPayload.Subscribe.F_OWNERSHIP).isNotZero();
    }

    @Test
    void resetMakesTheNextServerGetAFreshSubscribe() {
        DebugHudState state = new DebugHudState();
        SubscriptionManager m = new SubscriptionManager(state, () -> DebugPayload.Subscribe.F_REGIONS);
        m.onHello(2);
        Wire wire = new Wire();
        m.syncIfChanged(wire::accept);
        assertThat(m.syncIfChanged(wire::accept)).isFalse();

        m.reset();
        assertThat(m.lastSentMask()).isNull();

        m.onHello(2);
        assertThat(m.syncIfChanged(wire::accept)).isTrue();
        assertThat(wire.sent).hasSize(2);
    }
}
