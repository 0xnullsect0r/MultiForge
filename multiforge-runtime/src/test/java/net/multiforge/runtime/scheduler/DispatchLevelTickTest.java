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

import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    @AfterEach
    void resetPerWorldWarnOnceState() {
        // LevelTickDispatchProbes.warnedNoRegionizer and ViolationLogger's
        // rate-limit buckets / subscribers are all static, JVM-wide state —
        // clear them so the warn-once-per-world tests below don't leak into
        // each other (or into the probe-only tests above, which reuse some
        // of the same world-id strings).
        LevelTickDispatchProbes.resetForTesting();
        ViolationLogger.resetForTesting();
        ViolationLogger.clearSubscribersForTesting();
    }

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

    @Test
    void noRegionizerSkipWarnsOncePerWorldThenSilent() throws Exception {
        long before = ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE);
        List<ViolationLogger.ViolationEvent> received = new ArrayList<>();
        AutoCloseable subscription = ViolationLogger.subscribe(received::add);
        try {
            for (int i = 0; i < 5; i++) {
                LevelTickDispatchProbes.noRegionizerSkip("minecraft:the_end");
            }
        } finally {
            subscription.close();
        }

        // Probe counter bumps unconditionally — every call is counted even
        // though only the first one warns.
        assertThat(ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE) - before)
                .isEqualTo(5L);

        long warnsForTheEnd = received.stream()
                .filter(e -> e.site().equals(LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE + "::minecraft:the_end"))
                .count();
        assertThat(warnsForTheEnd).isEqualTo(1L);
    }

    @Test
    void noRegionizerSkipDistinctWorldsGetDistinctWarns() throws Exception {
        long before = ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE);
        List<ViolationLogger.ViolationEvent> received = new ArrayList<>();
        AutoCloseable subscription = ViolationLogger.subscribe(received::add);
        try {
            LevelTickDispatchProbes.noRegionizerSkip("minecraft:the_nether");
            LevelTickDispatchProbes.noRegionizerSkip("minecraft:the_end");
            LevelTickDispatchProbes.noRegionizerSkip("minecraft:custom_dim");
        } finally {
            subscription.close();
        }

        assertThat(ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE) - before)
                .isEqualTo(3L);
        assertThat(received)
                .extracting(ViolationLogger.ViolationEvent::site)
                .containsExactlyInAnyOrder(
                        LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE + "::minecraft:the_nether",
                        LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE + "::minecraft:the_end",
                        LevelTickDispatchProbes.NO_REGIONIZER_SKIP_PROBE + "::minecraft:custom_dim");
    }
}
