/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
        // Test-fixture bundled jars are never signed (no release-signing
        // pipeline runs for `./gradlew test`), so this dev-flow test opts
        // out of the --require-signed default explicitly. Fail-closed
        // behavior itself is covered by installFailsClosedWhenSignatureRequiredButSigMissing below.
        int code = Main.run(
                new String[] {"install", "--install-dir", dir.toString(), "--require-signed=false"}, out, out);
        assertThat(code).isZero();

        Path libs = dir.resolve("libraries/multiforge");
        assertThat(libs.resolve("multiforge-runtime.jar")).exists();
        assertThat(libs.resolve("multiforge-license.jar")).exists();
        assertThat(dir.resolve("run.sh")).exists();
        assertThat(dir.resolve("run.bat")).exists();
        assertThat(dir.resolve("config/multiforge-server.toml")).exists();
        assertThat(dir.resolve("eula.txt")).exists();
        assertThat(dir.resolve("license.key")).exists();
        assertThat(Files.readString(dir.resolve("eula.txt"))).contains("eula=false");
    }

    @Test
    void installPreservesExistingEulaAndConfig(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("config/multiforge-server.toml"), "cores = 32\n");

        Main.run(
                new String[] {"install", "--install-dir", dir.toString(), "--require-signed=false"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(Files.readString(dir.resolve("eula.txt"))).isEqualTo("eula=true\n");
        assertThat(Files.readString(dir.resolve("config/multiforge-server.toml")))
                .contains("cores = 32");
    }

    @Test
    void installAcceptsInlineLicenseToken(@TempDir Path dir) throws IOException {
        Main.run(
                new String[] {
                    "install", "--install-dir", dir.toString(), "--license", "eyJfake.token", "--require-signed=false"
                },
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        assertThat(Files.readString(dir.resolve("license.key"))).isEqualTo("eyJfake.token\n");
    }

    @Test
    void installAcceptsLicenseFromFile(@TempDir Path dir) throws IOException {
        Path tokenFile = dir.resolve("my-token.txt");
        Files.writeString(tokenFile, "eyJfromFile.token\n");
        Main.run(
                new String[] {
                    "install",
                    "--install-dir",
                    dir.resolve("srv").toString(),
                    "--license",
                    "@" + tokenFile.toAbsolutePath(),
                    "--require-signed=false"
                },
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        assertThat(Files.readString(dir.resolve("srv/license.key"))).isEqualTo("eyJfromFile.token\n");
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
                        "libraries/multiforge/multiforge-license.jar",
                        "run.multiforge.sh",
                        "run.multiforge.bat",
                        "config/multiforge-server.toml.example",
                        "README-MULTIFORGE.txt");
    }

    @Test
    void installFailsClosedWhenSignatureRequiredButSigMissing(@TempDir Path dir) throws IOException {
        // Default behavior (no --require-signed flag at all): the bundled
        // test-fixture jars carry no .sig, so this must abort with nothing
        // written to libraries/multiforge — round-6 fork C HIGH finding.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = Main.run(
                new String[] {"install", "--install-dir", dir.toString()},
                new PrintStream(out),
                new PrintStream(err));

        assertThat(code).isEqualTo(4);
        assertThat(err.toString()).contains("signature verification FAILED");
        assertThat(out.toString()).contains("refusing to install an unsigned artifact");
        assertThat(dir.resolve("libraries/multiforge/multiforge-runtime.jar")).doesNotExist();
        assertThat(dir.resolve("libraries/multiforge/multiforge-license.jar")).doesNotExist();
    }

    @Test
    void installFailsClosedWhenRequireSignedExplicitlyTrue(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = Main.run(
                new String[] {"install", "--install-dir", dir.toString(), "--require-signed=true"},
                new PrintStream(out),
                new PrintStream(err));

        assertThat(code).isEqualTo(4);
        assertThat(dir.resolve("libraries/multiforge/multiforge-runtime.jar")).doesNotExist();
    }

    @Test
    void installRejectsUnparseableRequireSignedValue(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = Main.run(
                new String[] {"install", "--install-dir", dir.toString(), "--require-signed=maybe"},
                new PrintStream(out),
                new PrintStream(err));

        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("--require-signed expects true|false");
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
