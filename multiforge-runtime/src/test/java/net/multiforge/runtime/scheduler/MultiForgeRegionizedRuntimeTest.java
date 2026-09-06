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
