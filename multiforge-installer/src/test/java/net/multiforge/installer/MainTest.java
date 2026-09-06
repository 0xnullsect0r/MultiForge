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
package net.multiforge.installer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @Test
    void installLaysOutLibsScriptsAndConfig(@TempDir Path dir) throws IOException {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        int code = Main.run(new String[] {"install", "--install-dir", dir.toString()}, out, out);
        assertThat(code).isZero();

        Path libs = dir.resolve("libraries/multiforge");
        assertThat(libs.resolve("multiforge-runtime.jar")).exists();
        assertThat(dir.resolve("run.sh")).exists();
        assertThat(dir.resolve("run.bat")).exists();
        assertThat(dir.resolve("config/multiforge-server.toml")).exists();
        assertThat(dir.resolve("eula.txt")).exists();
        assertThat(Files.readString(dir.resolve("eula.txt"))).contains("eula=false");
    }

    @Test
    void installPreservesExistingEulaAndConfig(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("config/multiforge-server.toml"), "cores = 32\n");

        Main.run(
                new String[] {"install", "--install-dir", dir.toString()},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(Files.readString(dir.resolve("eula.txt"))).isEqualTo("eula=true\n");
        assertThat(Files.readString(dir.resolve("config/multiforge-server.toml")))
                .contains("cores = 32");
    }

    @Test
    void buildZipEmitsExpectedEntries(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("multiforge-test-replacement.zip");
        int code = Main.run(
                new String[] {"build-zip", "--out", zip.toString()},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        assertThat(code).isZero();
        assertThat(zip).exists();

        Set<String> entries = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) entries.add(e.getName());
        }
        assertThat(entries)
                .contains(
                        "libraries/multiforge/multiforge-runtime.jar",
                        "run.multiforge.sh",
                        "run.multiforge.bat",
                        "config/multiforge-server.toml.example",
                        "README-MULTIFORGE.txt");
    }

    @Test
    void helpAndUnknownCommandExitCleanly() throws IOException {
        assertThat(Main.run(
                        new String[] {"help"},
                        new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())))
                .isZero();
        assertThat(Main.run(
                        new String[] {"nope"},
                        new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(new ByteArrayOutputStream())))
                .isEqualTo(2);
    }
}
