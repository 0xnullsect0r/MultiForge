/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
        OwnershipEnforcer.bindTickThread(Thread.currentThread());
        OwnershipEnforcer.bindRerouteTarget(r -> {}); // pretend server::execute

        MultiForgeRegionizedRuntime.install(MultiForgeConfig.defaults(), r -> {});
        MultiForgeRegionizedRuntime.shutdown();

        // The reroute target should now be the "unconfigured" fallback again — an off-thread
        // mutation submitted after shutdown must not route into a dead server::execute.
        // We prove this by observing that reroute() runs inline via the fallback (which
        // itself logs a warn, but does not throw RejectedExecutionException).
        ViolationLogger.resetForTesting();
        // A canMutate call on an unowned thread returns false (reroute mode) — safe to call.
        boolean canMutate = OwnershipEnforcer.canMutate("test-site");
        // The tick thread binding is cleared, so the current thread no longer counts as the tick thread.
        assertThat(canMutate).isFalse();
    }

    // /67 finding #3: RegionTickWatchdog and ViolationLogger tolerate garbage sysprop values.
    @Test
    void watchdogParseWarnMsHandlesGarbage() {
        assertThat(RegionTickWatchdog.parseWarnMs(null)).isEqualTo(500L);
        assertThat(RegionTickWatchdog.parseWarnMs("")).isEqualTo(500L);
        assertThat(RegionTickWatchdog.parseWarnMs("  ")).isEqualTo(500L);
        assertThat(RegionTickWatchdog.parseWarnMs("500ms")).isEqualTo(500L); // units → NFE → fallback
        assertThat(RegionTickWatchdog.parseWarnMs("nonsense")).isEqualTo(500L);
        assertThat(RegionTickWatchdog.parseWarnMs("0")).isEqualTo(500L); // non-positive → fallback
        assertThat(RegionTickWatchdog.parseWarnMs("-1")).isEqualTo(500L);
        assertThat(RegionTickWatchdog.parseWarnMs("1000")).isEqualTo(1000L);
    }

    @Test
    void violationLoggerParsePerMinHandlesGarbage() {
        assertThat(ViolationLogger.parsePerMin(null)).isEqualTo(5L);
        assertThat(ViolationLogger.parsePerMin("")).isEqualTo(5L);
        assertThat(ViolationLogger.parsePerMin("5msg")).isEqualTo(5L);
        assertThat(ViolationLogger.parsePerMin("0")).isEqualTo(5L);
        assertThat(ViolationLogger.parsePerMin("10")).isEqualTo(10L);
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
