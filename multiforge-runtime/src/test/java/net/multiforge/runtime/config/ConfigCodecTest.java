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
package net.multiforge.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigCodecTest {

    @Test
    void defaultsRoundTrip() {
        MultiForgeConfig original = MultiForgeConfig.defaults();
        MultiForgeConfig parsed = ConfigCodec.parse(ConfigCodec.render(original));
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void loadOfMissingFileReturnsDefaults(@TempDir Path tmp) throws IOException {
        MultiForgeConfig loaded = ConfigCodec.load(tmp.resolve("does-not-exist.toml"));
        assertThat(loaded).isEqualTo(MultiForgeConfig.defaults());
    }

    @Test
    void partialTomlOverridesOnlyExplicitKeys() {
        String toml =
                """
            [mtserver]
            cores = 12
            [region]
            mode = "full-world"
            """;
        MultiForgeConfig c = ConfigCodec.parse(toml);
        assertThat(c.cores()).isEqualTo(12);
        assertThat(c.regionMode()).isEqualTo(MultiForgeConfig.RegionMode.FULL_WORLD);
        // Everything else stays at defaults.
        assertThat(c.threadsPerCore()).isEqualTo(MultiForgeConfig.defaults().threadsPerCore());
        assertThat(c.violationPolicy()).isEqualTo(MultiForgeConfig.defaults().violationPolicy());
    }

    @Test
    void invalidEnumFailsFast() {
        String toml = """
            [mtserver]
            mode = "nonsense"
            """;
        assertThatThrownBy(() -> ConfigCodec.parse(toml)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveAndReloadThroughDisk(@TempDir Path tmp) throws IOException {
        MultiForgeConfig c = MultiForgeConfig.defaults().withCores(6).withThreadsPerCore(2);
        Path file = tmp.resolve("multiforge-server.toml");
        ConfigCodec.save(file, c);
        assertThat(Files.readString(file)).contains("cores = 6").contains("threadsPerCore = 2");
        MultiForgeConfig back = ConfigCodec.load(file);
        assertThat(back).isEqualTo(c);
    }
}
