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
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ViolationEmitterTest {

    @BeforeEach
    @AfterEach
    void resetViolationLogger() {
        ViolationLogger.resetForTesting();
        ViolationLogger.clearSubscribersForTesting();
    }

    @Test
    void emitTranslatesLoggerEventToWirePayload() {
        List<DebugPayload> received = new ArrayList<>();
        ViolationEmitter emitter = new ViolationEmitter(received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit(new ViolationLogger.ViolationEvent(123L, "kubejs", "ServerLevel.setBlock", "off-region write"));

        assertThat(received).hasSize(1);
        DebugPayload.ViolationEvent v = (DebugPayload.ViolationEvent) received.get(0);
        assertThat(v.epochMillis()).isEqualTo(123L);
        assertThat(v.modId()).isEqualTo("kubejs");
        assertThat(v.site()).isEqualTo("ServerLevel.setBlock");
        assertThat(v.detail()).isEqualTo("off-region write");
    }

    @Test
    void nullModIdIsSubstitutedWithEmptyStringSentinel() {
        List<DebugPayload> received = new ArrayList<>();
        ViolationEmitter emitter = new ViolationEmitter(received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit(new ViolationLogger.ViolationEvent(1L, null, "site", "detail"));

        DebugPayload.ViolationEvent v = (DebugPayload.ViolationEvent) received.get(0);
        assertThat(v.modId()).isEmpty();
    }

    @Test
    void installSubscribesToLiveViolationLoggerFanOut() throws Exception {
        List<DebugPayload> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.Set<String> senders = java.util.concurrent.ConcurrentHashMap.newKeySet();
        var host = new net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost(
                net.multiforge.runtime.config.MultiForgeConfig.defaults()
                        .withCores(1)
                        .withThreadsPerCore(1));
        AutoCloseable handle = ViolationEmitter.install(
                host,
                p -> {
                    senders.add(Thread.currentThread().getName());
                    received.add(p);
                },
                PermissionFilter.ALWAYS_ALLOW);
        try {
            ViolationLogger.warn("modA", "site1", "detail1");
            ViolationLogger.warn("modA", "site1", "detail1");
            ViolationLogger.warn("modA", "site1", "detail1");

            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> received.size() == 3);
            assertThat(received).allSatisfy(p -> assertThat(p).isInstanceOf(DebugPayload.ViolationEvent.class));
            // Frames are built and sent on the diagnostics thread, never on the
            // thread that logged the violation (a region worker in production).
            assertThat(senders).containsExactly("mf-diag-emitters");
        } finally {
            handle.close();
            host.close();
        }

        // Unsubscribed after close — no further payloads.
        ViolationLogger.warn("modA", "site1", "detail1");
        Thread.sleep(200);
        assertThat(received).hasSize(3);
    }

    @Test
    void goldenThreeEmitTicksProduceThreePayloads() {
        List<DebugPayload> received = new ArrayList<>();
        ViolationEmitter emitter = new ViolationEmitter(received::add, PermissionFilter.ALWAYS_ALLOW);

        emitter.emit(new ViolationLogger.ViolationEvent(1L, "a", "s", "d1"));
        emitter.emit(new ViolationLogger.ViolationEvent(2L, "a", "s", "d2"));
        emitter.emit(new ViolationLogger.ViolationEvent(3L, "a", "s", "d3"));

        assertThat(received).hasSize(3);
    }
}
