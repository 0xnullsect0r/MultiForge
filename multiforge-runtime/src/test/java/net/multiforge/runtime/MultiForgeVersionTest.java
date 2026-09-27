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
package net.multiforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import net.multiforge.api.MultiForgeApi;
import org.junit.jupiter.api.Test;

/**
 * The version baked into the api and runtime jars at build time. Both read a
 * templated properties file; through v1.7.0 the template used {@code @version@}
 * tokens that Gradle's {@code expand} never replaced, so every release reported
 * the literal "@version@" (the client HUD showed it).
 */
class MultiForgeVersionTest {
    @Test
    void runtimeVersionIsTheBuildVersion() {
        assertThat(MultiForge.VERSION).doesNotContain("@").doesNotContain("${").matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void apiVersionIsTheBuildVersion() {
        assertThat(MultiForgeApi.VERSION)
                .doesNotContain("@")
                .doesNotContain("${")
                .matches("\\d+\\.\\d+\\.\\d+.*");
    }
}
