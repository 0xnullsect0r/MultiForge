/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.multiforge.runtime.config.MultiForgeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MultiForgeRegionizedRuntimeTest {

    @AfterEach
    void cleanup() {
        MultiForgeRegionizedRuntime.shutdown();
    }

    @Test
    void freshInstallExposesTheHostViaCurrent() {
        assertThat(MultiForgeRegionizedRuntime.current()).isNull();
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.install(MultiForgeConfig.defaults(), r -> {});
        assertThat(host).isNotNull();
        assertThat(MultiForgeRegionizedRuntime.current()).isSameAs(host);
    }

    @Test
    void doubleInstallRejectedUntilShutdown() {
        MultiForgeRegionizedRuntime.install(MultiForgeConfig.defaults(), r -> {});
        assertThatThrownBy(() -> MultiForgeRegionizedRuntime.install(MultiForgeConfig.defaults(), r -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already installed");

        MultiForgeRegionizedRuntime.shutdown();
        // After shutdown, install works again.
        MultiThreadedSchedulerHost second = MultiForgeRegionizedRuntime.install(MultiForgeConfig.defaults(), r -> {});
        assertThat(second).isNotNull();
    }

    @Test
    void shutdownIsIdempotent() {
        MultiForgeRegionizedRuntime.install(MultiForgeConfig.defaults(), r -> {});
        MultiForgeRegionizedRuntime.shutdown();
        MultiForgeRegionizedRuntime.shutdown(); // must not throw
        assertThat(MultiForgeRegionizedRuntime.current()).isNull();
    }
}
