/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.globals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

/**
 * Round-6 fork B HIGH F2 — same-thread reentry into {@link
 * ScoreboardSystem#tryRoute}. The concrete repro this guards against: a
 * datapack function running on the global region worker does {@code
 * scoreboard players add @s counter 1} then {@code execute if score @s
 * counter matches 5..} in the same function — the {@code if} must read
 * the post-increment score, not a value that's stuck until next tick's
 * mailbox drain.
 */
class ScoreboardSystemReentrancyTest {

    @Test
    void offThreadCallStillEnqueuesOntoTheMailbox() {
        List<Runnable> enqueued = new ArrayList<>();
        RegionId globalId = RegionId.next();
        ScoreboardSystem sys = new ScoreboardSystem((dest, task) -> enqueued.add(task));
        sys.tick(new GlobalTickContext(1L, globalId));

        List<String> ran = new ArrayList<>();
        boolean accepted = sys.tryRoute(() -> ran.add("mutated"));

        assertThat(accepted).isTrue();
        assertThat(ran).isEmpty();
        assertThat(enqueued).hasSize(1);
        enqueued.get(0).run();
        assertThat(ran).containsExactly("mutated");
    }

    @Test
    void sameThreadReentrySynchronousMutationReturnsBeforeNextMailboxDrain() {
        List<Runnable> enqueued = new ArrayList<>();
        RegionId globalId = RegionId.next();
        ScoreboardSystem sys = new ScoreboardSystem((dest, task) -> enqueued.add(task));
        sys.tick(new GlobalTickContext(1L, globalId));

        List<String> ran = new ArrayList<>();
        boolean accepted = GlobalRegionThreadMarker.runMarked(() -> sys.tryRoute(() -> ran.add("mutated")));

        assertThat(accepted).isTrue();
        assertThat(ran).containsExactly("mutated");
        assertThat(enqueued).isEmpty();
    }

    @Test
    void readAfterWriteOnSameThreadSeesTheIncrementedScore() {
        RegionId globalId = RegionId.next();
        ScoreboardSystem sys = new ScoreboardSystem((dest, task) -> {
            throw new AssertionError("reentrant call must not hit the mailbox");
        });
        sys.tick(new GlobalTickContext(1L, globalId));

        // Simulates `scoreboard players add @s counter 1` followed by
        // `execute if score @s counter matches 5..` in the same
        // command-function invocation running on the global worker.
        int[] counter = {4};
        GlobalRegionThreadMarker.runMarked(() -> {
            sys.tryRoute(() -> counter[0] += 1);
            assertThat(counter[0]).isEqualTo(5);
            boolean[] conditionMatched = {false};
            sys.tryRoute(() -> conditionMatched[0] = counter[0] >= 5);
            assertThat(conditionMatched[0]).isTrue();
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
