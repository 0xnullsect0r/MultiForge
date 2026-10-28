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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the drop-in archive's shape and its scripts' content.
 *
 * <p>The previous version of these tests asserted only that files were
 * created — which is why the archive shipped for six releases with a
 * launcher pointing at {@code net.multiforge.runtime.bootstrap.Main}, a
 * class that has never existed. Creating a file proves nothing about
 * whether it works, so these assert on what the scripts actually say.
 */
class MainTest {

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    /** A stand-in for the fork installer; build-zip only copies bytes. */
    private static Path fakeInstaller(Path dir) throws IOException {
        Path jar = dir.resolve("neoforge-1.21.1-vX-installer.jar");
        Files.write(jar, "not-really-a-jar".getBytes(StandardCharsets.UTF_8));
        return jar;
    }

    private static Map<String, String> unzip(Path zip) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entries.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    void buildZipEmitsTheConverterAndTheInstaller(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("multiforge-replacement.zip");
        int code = Main.run(
                new String[] {
                    "build-zip",
                    "--out",
                    zip.toString(),
                    "--installer",
                    fakeInstaller(dir).toString()
                },
                sink(),
                sink());
        assertThat(code).isZero();

        Map<String, String> entries = unzip(zip);
        assertThat(entries.keySet())
                .containsExactlyInAnyOrder(
                        Main.INSTALLER_ENTRY,
                        "install-multiforge.sh",
                        "install-multiforge.bat",
                        "config/multiforge-server.toml.example",
                        "README-MULTIFORGE.txt");
        assertThat(entries.get(Main.INSTALLER_ENTRY)).isEqualTo("not-really-a-jar");
    }

    @Test
    void buildZipRequiresAnInstallerToWrapAround(@TempDir Path dir) throws IOException {
        // Without this the archive would ship scripts and no way to run
        // them — the shape that shipped broken through v1.4.1.
        assertThat(Main.run(
                        new String[] {"build-zip", "--out", dir.resolve("a.zip").toString()}, sink(), sink()))
                .isEqualTo(2);
        assertThat(dir.resolve("a.zip")).doesNotExist();
    }

    @Test
    void buildZipRejectsAMissingInstaller(@TempDir Path dir) throws IOException {
        assertThat(Main.run(
                        new String[] {
                            "build-zip",
                            "--out",
                            dir.resolve("a.zip").toString(),
                            "--installer",
                            dir.resolve("nope.jar").toString()
                        },
                        sink(),
                        sink()))
                .isEqualTo(2);
    }

    @Test
    void buildZipRequiresAnOutputPath(@TempDir Path dir) throws IOException {
        assertThat(Main.run(
                        new String[] {
                            "build-zip", "--installer", fakeInstaller(dir).toString()
                        },
                        sink(),
                        sink()))
                .isEqualTo(2);
    }

    @Test
    void noScriptReferencesAClassThatDoesNotExist() {
        // The regression guard. `net.multiforge.runtime.bootstrap.Main`
        // was never a real class; nothing may name it again, and nothing
        // may launch a Minecraft server off the runtime library alone.
        for (String script : new String[] {Main.installShScript(), Main.installBatScript()}) {
            assertThat(script).doesNotContain("net.multiforge.runtime.bootstrap");
            assertThat(script).doesNotContain("libraries/multiforge/*");
            assertThat(script).doesNotContain("libraries\\multiforge\\*");
        }
    }

    @Test
    void theConverterRunsTheForkInstallerAgainstTheCurrentDirectory() {
        assertThat(Main.installShScript()).contains("-jar multiforge/multiforge-installer.jar --installServer .");
        assertThat(Main.installBatScript()).contains("--installServer .");
    }

    @Test
    void theConverterPreflightsJdk21() {
        String sh = Main.installShScript();
        assertThat(sh).contains("JAVA_MAJOR");
        assertThat(sh).contains("!= \"21\"");
        assertThat(Main.installBatScript()).contains("if not \"%JAVA_MAJOR%\"==\"21\"");
    }

    @Test
    void theJdkFailureMessageActuallyInterpolatesTheVersion() {
        // v1.3.18's preflight used a *quoted* heredoc delimiter, so the
        // message added specifically to name the offending JDK printed
        // the literal string "${JAVA_MAJOR:-unknown}" instead. An
        // unquoted delimiter is what makes it expand.
        String sh = Main.installShScript();
        assertThat(sh).contains("<<PREFLIGHT_FAIL");
        assertThat(sh).doesNotContain("<<'PREFLIGHT_FAIL'");
    }

    @Test
    void theConverterBacksUpTheLauncherItReplaces() {
        String sh = Main.installShScript();
        assertThat(sh).contains("pre-multiforge");
        for (String f : new String[] {"run.sh", "run.bat", "user_jvm_args.txt"}) {
            assertThat(sh).as("backs up %s", f).contains(f);
        }
    }

    @Test
    void theInstallersOwnLauncherRefusesTheWrongJdk() throws Exception {
        // Stock NeoForge's run.sh calls bare `java`, so a correct install still died at
        // mod-scan on the wrong JDK, and every update (which rewrites run.sh) removed the
        // check the converter had added. The fork installer's template now carries it.
        java.nio.file.Path template = java.nio.file.Path.of("../upstream/neoforge-1.21.1/server_files/run.sh");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(template), "fork tree not present");
        String runSh = java.nio.file.Files.readString(template);
        assertThat(runSh)
                .contains("java\\.specification\\.version")
                .contains("!= \"21\"")
                .contains("exit 1");
        // NeoForged's server starter (server.jar) launches the first non-comment line whose
        // first word ends in "java" (run.sh) or is "java" (run.bat). A preflight line such as
        // `JVM=java` or `X=$(java ...` matched first and broke `java -jar server.jar`.
        String runBat = java.nio.file.Files.readString(template.resolveSibling("run.bat"));
        assertThat(starterLines(runSh, "#", w -> w.endsWith("java")))
                .containsExactly("java @user_jvm_args.txt @libraries/@MAVEN_PATH@/unix_args.txt \"$@\"");
        assertThat(starterLines(runBat, "REM", w -> w.equals("java") || w.endsWith("java.exe")))
                .containsExactly("java @user_jvm_args.txt @libraries/@MAVEN_PATH@/win_args.txt %*");
        assertThat(runBat).contains("java.specification.version").contains("exit /b 1");
        // The converter no longer re-wraps run.sh (it would drift from the template).
        assertThat(Main.installShScript()).doesNotContain("@ARGS_FILE");
    }

    /** The lines the server starter would consider launching, in order. */
    private static java.util.List<String> starterLines(
            String script, String comment, java.util.function.Predicate<String> firstWord) {
        return script.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith(comment) && !l.startsWith("@"))
                .filter(l -> firstWord.test(l.split("\\s+")[0]))
                .toList();
    }

    @Test
    void theConverterBacksUpOnlyOnce() {
        // A second run would back up the MultiForge launcher over the original.
        assertThat(Main.installShScript()).contains("! ls \"$f\".pre-multiforge-*.bak");
        assertThat(Main.installBatScript()).contains("if not exist run.bat.pre-multiforge.bak");
    }

    @Test
    void theConverterIsValidShell(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path script = tmp.resolve("install-multiforge.sh");
        java.nio.file.Files.writeString(script, Main.installShScript());
        Process bash;
        try {
            bash = new ProcessBuilder("bash", "-n", script.toString())
                    .redirectErrorStream(true)
                    .start();
        } catch (java.io.IOException noBash) {
            org.junit.jupiter.api.Assumptions.abort("no bash on this machine");
            return;
        }
        String out = new String(bash.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(bash.waitFor()).as(out).isZero();
    }

    @Test
    void theArchiveDocumentsWhyItShipsAnInstaller() {
        // The licensing constraint is the whole reason for this shape;
        // if someone deletes the explanation they will "simplify" the
        // archive straight back into being illegal to distribute.
        assertThat(Main.replacementReadme()).contains("Mojang");
        assertThat(Main.replacementReadme()).contains("redistributed");
    }

    @Test
    void defaultConfigIsTomlWithTheDocumentedKeys() {
        String toml = Main.defaultConfig();
        assertThat(toml).contains("cores = ").contains("threadsPerCore = ").contains("mode = \"hybrid\"");
        assertThat(toml).contains("[mtserver]").contains("[region]").contains("[violations]");
    }

    @Test
    void helpAndUnknownCommandExitCleanly() throws IOException {
        assertThat(Main.run(new String[] {"help"}, sink(), sink())).isZero();
        assertThat(Main.run(new String[] {"nope"}, sink(), sink())).isEqualTo(2);
    }

    @Test
    void theRemovedInstallSubcommandIsGone() throws IOException {
        // `install` laid out a directory whose run.sh had the same
        // nonexistent main class. It could never work either.
        assertThat(Main.run(new String[] {"install"}, sink(), sink())).isEqualTo(2);
    }
}
