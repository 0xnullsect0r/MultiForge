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

class ScoreboardSystemTest {

    @Test
    void routeBeforeAnyTickDropsWithoutThrowing() {
        List<Runnable> enqueued = new ArrayList<>();
        ScoreboardSystem sys = new ScoreboardSystem((dest, task) -> enqueued.add(task));

        sys.route(() -> {
            throw new AssertionError("must not run — nothing should be enqueued before the first tick()");
        });

        assertThat(enqueued).isEmpty();
    }

    @Test
    void routeAfterTickEnqueuesOntoTheGlobalRegion() {
        List<Runnable> enqueued = new ArrayList<>();
        RegionId globalId = RegionId.next();
        ScoreboardSystem sys = new ScoreboardSystem((dest, task) -> {
            assertThat(dest).isEqualTo(globalId);
            enqueued.add(task);
        });
        sys.tick(new GlobalTickContext(1L, globalId));

        List<String> ran = new ArrayList<>();
        sys.route(() -> ran.add("mutated"));

        assertThat(enqueued).hasSize(1);
        enqueued.get(0).run();
        assertThat(ran).containsExactly("mutated");
    }

    @Test
    void routedMutationThrowingIsIsolated() {
        List<Runnable> enqueued = new ArrayList<>();
        RegionId globalId = RegionId.next();
        ScoreboardSystem sys = new ScoreboardSystem((dest, task) -> enqueued.add(task));
        sys.tick(new GlobalTickContext(1L, globalId));

        sys.route(() -> {
            throw new RuntimeException("boom");
        });

        assertThat(enqueued).hasSize(1);
        // Running the enqueued wrapper must not propagate the mutation's exception.
        enqueued.get(0).run();
    }

    @Test
    void isReadyReflectsWhetherTickHasRunOnce() {
        ScoreboardSystem sys = new ScoreboardSystem((dest, task) -> {});
        assertThat(sys.isReady()).isFalse();
        sys.tick(new GlobalTickContext(1L, RegionId.next()));
        assertThat(sys.isReady()).isTrue();
    }

    @Test
    void tryRouteReturnsFalseBeforeReadyAndTrueAfter() {
        List<Runnable> enqueued = new ArrayList<>();
        ScoreboardSystem sys = new ScoreboardSystem((dest, task) -> enqueued.add(task));

        assertThat(sys.tryRoute(() -> {})).isFalse();
        assertThat(enqueued).isEmpty();

        sys.tick(new GlobalTickContext(1L, RegionId.next()));
        assertThat(sys.tryRoute(() -> {})).isTrue();
        assertThat(enqueued).hasSize(1);
    }

    @Test
    void nameIsStable() {
        assertThat(new ScoreboardSystem((dest, task) -> {}).name()).isEqualTo("scoreboard");
    }
}
