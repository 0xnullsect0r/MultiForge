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
            [violations]
            warnPerMin = 9
            """;
        MultiForgeConfig c = ConfigCodec.parse(toml);
        assertThat(c.cores()).isEqualTo(12);
        assertThat(c.warnPerMin()).isEqualTo(9);
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

    @Test
    void aFileFromBeforeTheTickSectionLoadsWithDefaultsAndIsLeftAlone(@TempDir Path tmp) throws IOException {
        // A v1.7 multiforge-server.toml: no [tick] section.
        String old =
                """
                [mtserver]
                cores = 6
                threadsPerCore = 1
                mode = "hybrid"

                [region]
                size = 3

                [violations]
                policy = "warn"
                warnPerMin = 9
                """;
        Path file = tmp.resolve("multiforge-server.toml");
        Files.writeString(file, old);

        MultiForgeConfigStore store = MultiForgeConfigStore.load(file);

        MultiForgeConfig c = store.get();
        assertThat(c.cores()).isEqualTo(6);
        assertThat(c.regionSize()).isEqualTo(3);
        assertThat(c.warnPerMin()).isEqualTo(9);
        assertThat(c.inlineSingleRegion()).isEqualTo(MultiForgeConfig.DEFAULT_INLINE_SINGLE_REGION);
        assertThat(c.serialLaneHotWaitMs()).isEqualTo(MultiForgeConfig.DEFAULT_SERIAL_LANE_HOT_WAIT_MS);
        assertThat(Files.readString(file)).isEqualTo(old); // loading never rewrites an existing file
    }

    @Test
    void tickKeysParseAndRoundTrip() {
        MultiForgeConfig c = ConfigCodec.parse(
                """
                [tick]
                inlineSingleRegion = false
                serialLaneHotWaitMs = 0
                """);
        assertThat(c.inlineSingleRegion()).isFalse();
        assertThat(c.serialLaneHotWaitMs()).isZero();
        assertThat(ConfigCodec.parse(ConfigCodec.render(c))).isEqualTo(c);
    }

    @Test
    void v18PostCountThresholdMapsOnlyItsNever() {
        assertThat(ConfigCodec.parse("[tick]\nserialLaneInlineThreshold = 0\n").serialLaneHotWaitMs())
                .isZero();
        assertThat(ConfigCodec.parse("[tick]\nserialLaneInlineThreshold = 2000\n")
                        .serialLaneHotWaitMs())
                .isEqualTo(MultiForgeConfig.DEFAULT_SERIAL_LANE_HOT_WAIT_MS);
        assertThat(ConfigCodec.parse("[tick]\nserialLaneInlineThreshold = 0\nserialLaneHotWaitMs = 7\n")
                        .serialLaneHotWaitMs())
                .isEqualTo(7);
    }

    @Test
    void deferVisibilityDefaultsOnAndRoundTrips() {
        assertThat(MultiForgeConfig.defaults().deferVisibility()).isTrue();
        assertThat(ConfigCodec.parse("[mtserver]\ncores = 2\n").deferVisibility())
                .isTrue();
        MultiForgeConfig off = ConfigCodec.parse("[entities]\ndeferVisibility = false\n");
        assertThat(off.deferVisibility()).isFalse();
        assertThat(ConfigCodec.parse(ConfigCodec.render(off))).isEqualTo(off);
        assertThat(MultiForgeConfig.defaults().withDeferVisibility(false)).isEqualTo(off);
    }

    @Test
    void deferVisibilitySystemPropertyWins() {
        String prev = System.getProperty("multiforge.entities.deferVisibility");
        try {
            System.setProperty("multiforge.entities.deferVisibility", "false");
            assertThat(MultiForgeConfig.defaults().effectiveDeferVisibility()).isFalse();
            System.setProperty("multiforge.entities.deferVisibility", "true");
            assertThat(MultiForgeConfig.defaults().withDeferVisibility(false).effectiveDeferVisibility())
                    .isTrue();
        } finally {
            if (prev == null) System.clearProperty("multiforge.entities.deferVisibility");
            else System.setProperty("multiforge.entities.deferVisibility", prev);
        }
    }
}
