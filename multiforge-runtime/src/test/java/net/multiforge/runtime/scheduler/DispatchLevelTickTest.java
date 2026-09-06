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
package net.multiforge.runtime.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import net.multiforge.runtime.diagnostics.ProbeRegistry;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the B3.5 no-fallback skip paths in the fork façade's
 * {@code RegionizedTickCoordinator.dispatchLevelTick} (docs/design/
 * m13-b3-region-tick.md §2; frozen target shape at docs/design/
 * global-region.md §6.4). That method itself lives under {@code
 * upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/} and
 * takes a {@code net.minecraft.server.level.ServerLevel}, so it cannot be
 * unit-tested from {@code multiforge-runtime} (no Minecraft classpath,
 * matching every other B3 phase-body test in this package/tree). Instead
 * this exercises {@link LevelTickDispatchProbes} directly — the pure-Java
 * helper {@code dispatchLevelTick} delegates its bootstrap, no-regionizer,
 * and dispatch-failure branches to, verbatim (same probe keys, same
 * warn-then-return shape, no callback invoked).
 *
 * <p>Post-B3.5 there is no {@code Runnable vanillaBody} anywhere in the
 * dispatch path — {@code git grep vanillaBody upstream/neoforge-1.21.1/
 * src/main/java/net/multiforge/} returns zero matches. Each method under
 * test here takes only label/exception arguments (no {@code Runnable}
 * parameter exists to invoke), so "the runtime never calls vanillaBody"
 * holds by construction, not merely by assertion.
 */
class DispatchLevelTickTest {

    @Test
    void bootstrapSkipBumpsItsOwnProbe() {
        long before = ProbeRegistry.get(LevelTickDispatchProbes.BOOTSTRAP_SKIP_PROBE);

        LevelTickDispatchProbes.bootstrapSkip("minecraft:overworld");

        assertThat(ProbeRegistry.get(LevelTickDispatchProbes.BOOTSTRAP_SKIP_PROBE))
                .isGreaterThan(before);
    }

    @Test
    void noRegionizerSkipBumpsItsOwnProbe() {
        long before = ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE);

        LevelTickDispatchProbes.noRegionizerSkip("minecraft:the_nether");

        assertThat(ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE))
                .isGreaterThan(before);
    }

    @Test
    void dispatchFailureBumpsItsOwnProbe() {
        long before = ProbeRegistry.get(LevelTickDispatchProbes.DISPATCH_FAILURE_PROBE);

        LevelTickDispatchProbes.dispatchFailure("minecraft:the_end", new IllegalStateException("boom"));

        assertThat(ProbeRegistry.get(LevelTickDispatchProbes.DISPATCH_FAILURE_PROBE))
                .isGreaterThan(before);
    }

    @Test
    void probesAreIndependent() {
        // Each skip path must bump only its own named probe — a shared
        // counter would defeat the operator's ability to tell bootstrap
        // skips apart from no-regionizer skips or dispatch failures.
        long bootstrapBefore = ProbeRegistry.get(LevelTickDispatchProbes.BOOTSTRAP_SKIP_PROBE);
        long noRegionizerBefore = ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE);
        long dispatchFailureBefore = ProbeRegistry.get(LevelTickDispatchProbes.DISPATCH_FAILURE_PROBE);

        LevelTickDispatchProbes.bootstrapSkip("minecraft:overworld");

        assertThat(ProbeRegistry.get(LevelTickDispatchProbes.BOOTSTRAP_SKIP_PROBE))
                .isGreaterThan(bootstrapBefore);
        assertThat(ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE))
                .isEqualTo(noRegionizerBefore);
        assertThat(ProbeRegistry.get(LevelTickDispatchProbes.DISPATCH_FAILURE_PROBE))
                .isEqualTo(dispatchFailureBefore);
    }
}
