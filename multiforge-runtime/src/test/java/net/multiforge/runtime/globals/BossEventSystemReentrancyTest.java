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
package net.multiforge.runtime.globals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

/**
 * Round-6 fork B HIGH F2 — same-thread reentry into {@link
 * BossEventSystem#tryRoute}. When the caller is already running on the
 * global region worker (inside {@link GlobalRegionThreadMarker#runMarked}),
 * the mutation must run synchronously instead of being deferred to the
 * next tick's mailbox drain — otherwise a same-tick read-after-write
 * (e.g. a bossbar create immediately followed by a read of its state)
 * would observe stale state for a whole tick.
 */
class BossEventSystemReentrancyTest {

    @Test
    void offThreadCallStillEnqueuesOntoTheMailbox() {
        List<Runnable> enqueued = new ArrayList<>();
        RegionId globalId = RegionId.next();
        BossEventSystem sys = new BossEventSystem((dest, task) -> enqueued.add(task));
        sys.tick(new GlobalTickContext(1L, globalId));

        List<String> ran = new ArrayList<>();
        boolean accepted = sys.tryRoute(() -> ran.add("mutated"));

        assertThat(accepted).isTrue();
        // Not run yet — this call was off the global-region worker thread,
        // so it must still take the mailbox-hop path.
        assertThat(ran).isEmpty();
        assertThat(enqueued).hasSize(1);
        enqueued.get(0).run();
        assertThat(ran).containsExactly("mutated");
    }

    @Test
    void sameThreadReentrySynchronousMutationReturnsBeforeNextMailboxDrain() {
        List<Runnable> enqueued = new ArrayList<>();
        RegionId globalId = RegionId.next();
        BossEventSystem sys = new BossEventSystem((dest, task) -> enqueued.add(task));
        sys.tick(new GlobalTickContext(1L, globalId));

        List<String> ran = new ArrayList<>();
        boolean accepted = GlobalRegionThreadMarker.runMarked(() -> sys.tryRoute(() -> ran.add("mutated")));

        assertThat(accepted).isTrue();
        // Ran inline, synchronously — before tryRoute() even returned, and
        // definitely before any mailbox drain would have happened.
        assertThat(ran).containsExactly("mutated");
        // Nothing was queued for a later drain: the reentrant path never
        // touches the mailbox.
        assertThat(enqueued).isEmpty();
    }

    @Test
    void readAfterWriteOnSameThreadSeesTheNewValue() {
        RegionId globalId = RegionId.next();
        BossEventSystem sys = new BossEventSystem((dest, task) -> {
            throw new AssertionError("reentrant call must not hit the mailbox");
        });
        sys.tick(new GlobalTickContext(1L, globalId));

        int[] state = {0};
        GlobalRegionThreadMarker.runMarked(() -> {
            sys.tryRoute(() -> state[0] = 5);
            // Read-after-write, same tick, same thread: must observe the
            // mutation immediately, not a stale pre-mutation value.
            assertThat(state[0]).isEqualTo(5);
            return null;
        });
    }

    @Test
    void markerIsClearedAfterRunMarkedEvenOnThrow() {
        assertThat(GlobalRegionThreadMarker.isCurrent()).isFalse();
        try {
            GlobalRegionThreadMarker.runMarked(() -> {
                throw new RuntimeException("boom");
            });
        } catch (RuntimeException expected) {
            // expected
        }
        assertThat(GlobalRegionThreadMarker.isCurrent()).isFalse();
    }
}
