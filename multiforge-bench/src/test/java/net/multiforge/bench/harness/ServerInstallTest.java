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
package net.multiforge.bench.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerInstallTest {
    private static final String LIBS = "libraries/net/neoforged/neoforge/";

    private static void version(Path dir, String v) throws IOException {
        Files.createDirectories(dir.resolve(LIBS + v));
        Files.writeString(dir.resolve(LIBS + v + "/unix_args.txt"), "");
    }

    private static String launched(Path dir) throws IOException {
        return ServerInstall.open(dir).orElseThrow().command(List.of()).stream()
                .filter(a -> a.startsWith("@libraries"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void picksTheVersionRunShLaunches(@TempDir Path dir) throws IOException {
        version(dir, "21.1.251-multiforge-1.7.1");
        version(dir, "21.1.251-multiforge-1.8.0");
        Files.writeString(
                dir.resolve("run.sh"),
                "java @user_jvm_args.txt @" + LIBS + "21.1.251-multiforge-1.7.1/unix_args.txt \"$@\"\n");
        assertThat(launched(dir)).isEqualTo("@" + LIBS + "21.1.251-multiforge-1.7.1/unix_args.txt");
    }

    @Test
    void withoutRunShPicksTheHighestVersionNumerically(@TempDir Path dir) throws IOException {
        version(dir, "21.1.251-multiforge-1.9.0");
        version(dir, "21.1.251-multiforge-1.10.0");
        assertThat(launched(dir)).isEqualTo("@" + LIBS + "21.1.251-multiforge-1.10.0/unix_args.txt");
    }

    @Test
    void comparesDigitRunsAsNumbers() {
        assertThat(ServerInstall.compareVersions("1.10.0", "1.9.0")).isPositive();
        assertThat(ServerInstall.compareVersions("1.7.1", "1.7.1")).isZero();
        assertThat(ServerInstall.compareVersions("1.7", "1.7.1")).isNegative();
    }
}
