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
package net.multiforge.runtime.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OwnershipEnforcerTest {

    @AfterEach
    void reset() {
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.REROUTE);
        OwnershipEnforcer.clearTickThreadForTesting();
        OwnershipEnforcer.resetRerouteTargetForTesting();
        ProbeRegistry.resetForTesting();
        ViolationLogger.resetForTesting();
    }

    @Test
    void offModeAlwaysAllowsInline() {
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.OFF);
        assertThat(OwnershipEnforcer.canMutate("Level.setBlock")).isTrue();
        assertThat(ProbeRegistry.get("Level.setBlock:off-thread")).isZero();
    }

    @Test
    void regionDomainPassesThroughEvenOffTickThread() {
        OwnerToken.runAs(OwnerToken.forRegion(1L), () -> {
            assertThat(OwnershipEnforcer.canMutate("Level.setBlock")).isTrue();
        });
        assertThat(ProbeRegistry.get("Level.setBlock:off-thread")).isZero();
    }

    @Test
    void globalDomainPassesThrough() {
        OwnerToken.runAs(OwnerToken.GLOBAL, () -> {
            assertThat(OwnershipEnforcer.canMutate("Level.setBlock")).isTrue();
        });
        assertThat(ProbeRegistry.get("Level.setBlock:off-thread")).isZero();
    }

    @Test
    void boundTickThreadPassesThroughWithUnknownDomain() {
        OwnershipEnforcer.bindTickThread(Thread.currentThread());
        assertThat(OwnerToken.current().domain()).isEqualTo(Domain.UNKNOWN);
        assertThat(OwnershipEnforcer.canMutate("Level.setBlock")).isTrue();
        assertThat(ProbeRegistry.get("Level.setBlock:off-thread")).isZero();
    }

    @Test
    void offThreadUnknownDomainIsViolationAndRerouteModeReturnsFalse() {
        assertThat(OwnershipEnforcer.canMutate("Level.setBlock")).isFalse();
        assertThat(ProbeRegistry.get("Level.setBlock:off-thread")).isEqualTo(1L);
    }

    @Test
    void strictModeThrowsInsteadOfReturningFalse() {
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.STRICT);
        assertThatThrownBy(() -> OwnershipEnforcer.canMutate("Level.setBlock"))
                .isInstanceOf(OwnershipViolationException.class)
                .hasMessageContaining("Level.setBlock");
        assertThat(ProbeRegistry.get("Level.setBlock:off-thread")).isEqualTo(1L);
    }

    @Test
    void strictModeNeverThrowsForTheBoundTickThread() {
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.STRICT);
        OwnershipEnforcer.bindTickThread(Thread.currentThread());
        assertThat(OwnershipEnforcer.canMutate("Level.setBlock")).isTrue();
    }

    @Test
    void rerouteDelegatesToBoundTarget() {
        AtomicReference<Runnable> captured = new AtomicReference<>();
        OwnershipEnforcer.bindRerouteTarget(captured::set);
        AtomicBoolean ran = new AtomicBoolean();
        OwnershipEnforcer.reroute("Level.setBlock", () -> ran.set(true));
        assertThat(ran).isFalse(); // not run inline by reroute() itself
        captured.get().run();
        assertThat(ran).isTrue();
    }

    @Test
    void unconfiguredRerouteTargetRunsInlineRatherThanCrashing() {
        AtomicBoolean ran = new AtomicBoolean();
        OwnershipEnforcer.reroute("Level.setBlock", () -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void unrecognizedModePropertyFallsBackToReroute() {
        assertThat(OwnershipEnforcer.parseMode("bogus")).isEqualTo(OwnershipEnforcer.Mode.REROUTE);
        assertThat(OwnershipEnforcer.parseMode("strict")).isEqualTo(OwnershipEnforcer.Mode.STRICT);
        assertThat(OwnershipEnforcer.parseMode("OFF")).isEqualTo(OwnershipEnforcer.Mode.OFF);
    }
}
