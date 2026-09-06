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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.spi.SchedulerHost;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.ownership.OwnershipEnforcer;
import net.multiforge.runtime.region.RegionTickWatchdog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the /67 review's lifecycle findings.
 */
class RuntimeLifecycleReviewFixesTest {

    @AfterEach
    void cleanup() {
        MultiForgeRegionizedRuntime.shutdown();
        ServerDomains.uninstall(); // in case a test bound a fake host without going through install
    }

    // /67 finding #7: install must roll back CURRENT when host.install() throws.
    @Test
    void installRollsBackCurrentWhenServerDomainsRejects() {
        // Simulate: a stale HOST is bound to ServerDomains (test scaffolding, or a prior JVM's crash residue).
        SchedulerHost stale = new StubSchedulerHost();
        ServerDomains.install(stale);

        assertThatThrownBy(() -> MultiForgeRegionizedRuntime.install(MultiForgeConfig.defaults(), r -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already installed");

        // Rollback assertion: CURRENT must be null — no split-brain where
        // current() returns a fresh host while ServerDomains routes elsewhere.
        assertThat(MultiForgeRegionizedRuntime.current()).isNull();

        // After clearing the stale binding, install succeeds — proving the
        // rollback also released whatever resources the failed install allocated.
        ServerDomains.uninstall();
        MultiThreadedSchedulerHost fresh = MultiForgeRegionizedRuntime.install(MultiForgeConfig.defaults(), r -> {});
        assertThat(fresh).isNotNull();
        assertThat(MultiForgeRegionizedRuntime.current()).isSameAs(fresh);
    }

    // /67 finding #10: shutdown must unbind OwnershipEnforcer's tick-thread and reroute-target.
    @Test
    void shutdownUnbindsOwnershipEnforcerBindings() {
        // Sentinel that captures whether the PRE-shutdown reroute target
        // ever fires. If shutdown correctly unbinds the target, a
        // reroute() after shutdown routes through the "unconfigured
        // fallback" instead — so this sentinel must remain false.
        // /67 round-4 fix (C2): the previous test only asserted the
        // tick-thread half of the unbind. If a regression left the
        // reroute target pointing at a dying server executor, the
        // previous test still passed. This half completes the coverage.
        java.util.concurrent.atomic.AtomicBoolean staleTargetFired =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        OwnershipEnforcer.bindTickThread(Thread.currentThread());
        OwnershipEnforcer.bindRerouteTarget(r -> staleTargetFired.set(true));

        MultiForgeRegionizedRuntime.install(MultiForgeConfig.defaults(), r -> {});
        MultiForgeRegionizedRuntime.shutdown();

        // The tick thread binding is cleared, so the current thread no longer counts as the tick thread.
        ViolationLogger.resetForTesting();
        boolean canMutate = OwnershipEnforcer.canMutate("test-site");
        assertThat(canMutate).isFalse();

        // Now the reroute-target check: an off-thread reroute submitted
        // AFTER shutdown must NOT reach the pre-shutdown binding (which
        // in production would be server::execute on a dying server, and
        // would throw RejectedExecutionException). It should route
        // through the unconfigured fallback instead, which runs the
        // mutation inline + logs a warn.
        java.util.concurrent.atomic.AtomicBoolean mutationRan = new java.util.concurrent.atomic.AtomicBoolean(false);
        OwnershipEnforcer.reroute("test-site", () -> mutationRan.set(true));
        assertThat(staleTargetFired).isFalse();
        assertThat(mutationRan).isTrue(); // fallback ran it inline
    }

    // /67 finding #3: RegionTickWatchdog and ViolationLogger tolerate garbage sysprop values.
    // Post round-2 revert: `0` is now a LEGITIMATE value (always-warn / silence), only
    // negative and non-numeric fall back to the default.
    @Test
    void watchdogParseWarnMsHandlesGarbage() {
        assertThat(RegionTickWatchdog.parseWarnMs(null)).isEqualTo(500L);
        assertThat(RegionTickWatchdog.parseWarnMs("")).isEqualTo(500L);
        assertThat(RegionTickWatchdog.parseWarnMs("  ")).isEqualTo(500L);
        assertThat(RegionTickWatchdog.parseWarnMs("500ms")).isEqualTo(500L); // units → NFE → fallback
        assertThat(RegionTickWatchdog.parseWarnMs("nonsense")).isEqualTo(500L);
        assertThat(RegionTickWatchdog.parseWarnMs("0")).isEqualTo(0L); // always-warn, legit
        assertThat(RegionTickWatchdog.parseWarnMs("-1")).isEqualTo(500L); // negative → fallback
        assertThat(RegionTickWatchdog.parseWarnMs("1000")).isEqualTo(1000L);
    }

    @Test
    void violationLoggerParsePerMinHandlesGarbage() {
        assertThat(ViolationLogger.parsePerMin(null)).isEqualTo(5L);
        assertThat(ViolationLogger.parsePerMin("")).isEqualTo(5L);
        assertThat(ViolationLogger.parsePerMin("5msg")).isEqualTo(5L);
        assertThat(ViolationLogger.parsePerMin("0")).isEqualTo(0L); // silence all warnings, legit
        assertThat(ViolationLogger.parsePerMin("-1")).isEqualTo(5L);
        assertThat(ViolationLogger.parsePerMin("10")).isEqualTo(10L);
    }

    // /67 round-3 finding: `parsePerMin("0")` was documented as "silence all warnings" but
    // Bucket.tryConsume with capacity=0 returned 1 on first call → still emitted the
    // "further identical messages suppressed" WARN per site per window. Not silence.
    @Test
    void violationLoggerPerMinZeroActuallySilencesInsteadOfFiringOnce() {
        ViolationLogger.resetForTesting();
        // Set up a bucket with capacity 0 by directly using the ViolationLogger.warn
        // path — but we can't set PER_MIN dynamically (it's final). Instead, verify the
        // Bucket contract at capacity=0 via an indirect proof: with the fix,
        // Bucket.tryConsume returns 2 (silent drop) unconditionally. Without the fix,
        // it returned 1 (fire suppressing note) on the first call. Since PER_MIN is
        // parsed at class-load, we verify by reflection into Bucket directly.
        try {
            var bucketClass = Class.forName("net.multiforge.runtime.diagnostics.ViolationLogger$Bucket");
            var ctor = bucketClass.getDeclaredConstructor(long.class, long.class);
            ctor.setAccessible(true);
            Object bucket = ctor.newInstance(0L, 60_000_000_000L);
            var tryConsume = bucketClass.getDeclaredMethod("tryConsume");
            tryConsume.setAccessible(true);
            long first = (Long) tryConsume.invoke(bucket);
            long second = (Long) tryConsume.invoke(bucket);
            long third = (Long) tryConsume.invoke(bucket);
            // All calls with capacity=0 must return 2 (silent drop); no "1" for the
            // suppressing note branch — that would emit one WARN per site.
            assertThat(first).isEqualTo(2L);
            assertThat(second).isEqualTo(2L);
            assertThat(third).isEqualTo(2L);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("reflection into ViolationLogger.Bucket failed", e);
        }
    }

    /** Minimal SchedulerHost to occupy the ServerDomains binding. Never actually invoked. */
    private static final class StubSchedulerHost implements SchedulerHost {
        @Override
        public net.multiforge.api.scheduler.RegionDomain region(
                net.multiforge.api.world.WorldRef w, net.multiforge.api.world.ChunkPos p) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public net.multiforge.api.scheduler.EntityDomain entity(net.multiforge.api.entity.EntityRef e) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public net.multiforge.api.scheduler.GlobalDomain global() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public net.multiforge.api.scheduler.AsyncDomain async() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
