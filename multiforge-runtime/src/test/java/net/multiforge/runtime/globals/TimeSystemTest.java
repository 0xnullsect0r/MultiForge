/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.globals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

class TimeSystemTest {

    private static final WorldRef OVERWORLD = WorldRef.of("minecraft:overworld");

    @Test
    void tickTimeRunsEveryCall() {
        TimeSystem sys = new TimeSystem((dest, task) -> {});
        List<String> events = new ArrayList<>();
        sys.registerWorld(OVERWORLD, new TimeSystem.TimeTarget() {
            @Override
            public void tickTime() {
                events.add("tick");
            }

            @Override
            public void broadcastTime() {
                events.add("broadcast");
            }
        });
        RegionId globalId = RegionId.next();

        for (long tick = 1; tick <= 5; tick++) {
            sys.tick(new GlobalTickContext(tick, globalId));
        }

        assertThat(events).hasSize(5).allMatch("tick"::equals);
    }

    @Test
    void broadcastFiresOnlyEveryTwentiethTickViaCrossRegionEffect() {
        List<Runnable> enqueued = new ArrayList<>();
        RegionId globalId = RegionId.next();
        TimeSystem sys = new TimeSystem((dest, task) -> {
            assertThat(dest).isEqualTo(globalId);
            enqueued.add(task);
        });
        List<String> events = new ArrayList<>();
        sys.registerWorld(OVERWORLD, new TimeSystem.TimeTarget() {
            @Override
            public void tickTime() {}

            @Override
            public void broadcastTime() {
                events.add("broadcast");
            }
        });

        for (long tick = 1; tick <= 40; tick++) {
            sys.tick(new GlobalTickContext(tick, globalId));
        }

        // ticks 20 and 40 enqueue a broadcast task each; running them fires broadcastTime().
        assertThat(enqueued).hasSize(2);
        enqueued.forEach(Runnable::run);
        assertThat(events).containsExactly("broadcast", "broadcast");
    }

    @Test
    void tickTimeFailureIsIsolatedAndSkipsThatWorldsBroadcast() {
        List<Runnable> enqueued = new ArrayList<>();
        RegionId globalId = RegionId.next();
        TimeSystem sys = new TimeSystem((dest, task) -> enqueued.add(task));
        sys.registerWorld(OVERWORLD, new TimeSystem.TimeTarget() {
            @Override
            public void tickTime() {
                throw new RuntimeException("boom");
            }

            @Override
            public void broadcastTime() {
                throw new AssertionError("must not be reached — tickTime failed first");
            }
        });

        sys.tick(new GlobalTickContext(20L, globalId));

        assertThat(enqueued).isEmpty();
    }

    @Test
    void isHandlingReflectsRegistrationState() {
        TimeSystem sys = new TimeSystem((dest, task) -> {});
        assertThat(sys.isHandling(OVERWORLD)).isFalse();
        sys.registerWorld(OVERWORLD, new TimeSystem.TimeTarget() {
            @Override
            public void tickTime() {}

            @Override
            public void broadcastTime() {}
        });
        assertThat(sys.isHandling(OVERWORLD)).isTrue();
        sys.unregisterWorld(OVERWORLD);
        assertThat(sys.isHandling(OVERWORLD)).isFalse();
    }

    @Test
    void nameIsStable() {
        assertThat(new TimeSystem((dest, task) -> {}).name()).isEqualTo("time");
    }
}
