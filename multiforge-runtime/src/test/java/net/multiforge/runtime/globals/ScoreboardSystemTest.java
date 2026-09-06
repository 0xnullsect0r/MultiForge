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
