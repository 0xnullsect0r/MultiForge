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
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RegionPhaseTest {

    @AfterEach
    void reset() {
        RegionPhase.resetForTesting();
        RegionTickWatchdog.setMode(RegionTickWatchdog.Mode.WARN);
        ViolationLogger.resetForTesting();
    }

    @Test
    void outsideThePhaseNothingIsDeferred() {
        List<String> ran = new ArrayList<>();
        assertThat(RegionPhase.workersInFlight()).isFalse();
        assertThat(RegionPhase.defer(() -> ran.add("a"))).isFalse();
        assertThat(RegionPhase.pending()).isZero();
        assertThat(ran).isEmpty();
    }

    @Test
    void holdsCallsWhileWorkersRunAndReplaysThemInOrder() {
        List<String> ran = new ArrayList<>();
        RegionPhase.beginWorkers();
        assertThat(RegionPhase.defer(() -> ran.add("demote"))).isTrue();
        assertThat(RegionPhase.defer(() -> ran.add("promote"))).isTrue();
        assertThat(RegionPhase.defer(() -> ran.add("demote-again"))).isTrue();
        assertThat(ran).isEmpty();
        assertThat(RegionPhase.pending()).isEqualTo(3);
        RegionPhase.endWorkers();
        assertThat(ran).containsExactly("demote", "promote", "demote-again");
        assertThat(RegionPhase.workersInFlight()).isFalse();
        assertThat(RegionPhase.pending()).isZero();
    }

    @Test
    void aReplayRunsWithTheFlagClearSoItAppliesInsteadOfDeferringAgain() {
        List<Boolean> deferredOnReplay = new ArrayList<>();
        RegionPhase.beginWorkers();
        RegionPhase.defer(() -> deferredOnReplay.add(RegionPhase.defer(() -> {})));
        RegionPhase.endWorkers();
        assertThat(deferredOnReplay).containsExactly(false);
    }

    @Test
    void aFailingReplayIsLoggedAndTheRestStillRun() {
        List<String> ran = new ArrayList<>();
        long failed = ProbeRegistry.get("entity.visibility.replay-failed");
        RegionPhase.beginWorkers();
        RegionPhase.defer(() -> {
            throw new IllegalStateException("boom");
        });
        RegionPhase.defer(() -> ran.add("after"));
        RegionPhase.endWorkers();
        assertThat(ran).containsExactly("after");
        assertThat(ProbeRegistry.get("entity.visibility.replay-failed")).isEqualTo(failed + 1);
    }

    @Test
    void killSwitchRunsInlineAndCountsIt() {
        RegionPhase.setDeferVisibility(false);
        long unguarded = ProbeRegistry.get("entity.visibility.unguarded");
        RegionPhase.beginWorkers();
        assertThat(RegionPhase.defer(() -> {})).isFalse();
        RegionPhase.endWorkers();
        assertThat(ProbeRegistry.get("entity.visibility.unguarded")).isEqualTo(unguarded + 1);
    }

    @Test
    void strictModeFailsTheBarrierOnAnUnguardedTransition() {
        RegionPhase.setDeferVisibility(false);
        RegionTickWatchdog.setMode(RegionTickWatchdog.Mode.STRICT);
        RegionPhase.beginWorkers();
        RegionPhase.defer(() -> {});
        assertThatThrownBy(RegionPhase::endWorkers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deferVisibility is off");
        assertThat(RegionPhase.workersInFlight()).isFalse();
    }

    @Test
    void strictModeIsQuietWhenEverythingWasDeferred() {
        RegionTickWatchdog.setMode(RegionTickWatchdog.Mode.STRICT);
        RegionPhase.beginWorkers();
        RegionPhase.defer(() -> {});
        RegionPhase.endWorkers();
    }
}
