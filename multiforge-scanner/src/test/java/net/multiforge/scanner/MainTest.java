/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.multiforge.scanner.rules.R05EntitySetPosOffCoord;
import net.multiforge.scanner.testsupport.Bytecode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

class MainTest {

    @TempDir
    Path tempDir;

    @Test
    void exitsTwoOnUsageError() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
                new String[0], new PrintStream(out), new PrintStream(err), List.of(new R05EntitySetPosOffCoord()));
        assertThat(code).isEqualTo(2);
    }

    @Test
    void exitsOneWhenErrorFindingPresentAndPrintsJson() throws IOException {
        Path jar = writeJarWithBadR05Class();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = Main.run(
                new String[] {jar.toString()},
                new PrintStream(out),
                new PrintStream(err),
                List.of(new R05EntitySetPosOffCoord()));

        assertThat(code).isEqualTo(1);
        assertThat(out.toString()).contains("\"R05\"");
    }

    @Test
    void exitsZeroWhenNoFindings() throws IOException {
        Path jar = writeEmptyJar();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = Main.run(
                new String[] {jar.toString()},
                new PrintStream(out),
                new PrintStream(err),
                List.of(new R05EntitySetPosOffCoord()));

        assertThat(code).isEqualTo(0);
    }

    @Test
    void ignoreFileSuppressesAMatchingFindingAndDropsExitCodeToZero() throws IOException {
        Path jar = writeJarWithBadR05Class();
        Path ignoreFile = tempDir.resolve("my.multiforgeignore");
        // First run un-suppressed to discover the exact fingerprint the fixture produces —
        // mirrors the real workflow of "run once, paste the reported fingerprint to suppress it."
        ByteArrayOutputStream discover = new ByteArrayOutputStream();
        Main.run(
                new String[] {jar.toString()},
                new PrintStream(discover),
                new PrintStream(new ByteArrayOutputStream()),
                List.of(new R05EntitySetPosOffCoord()));
        String fingerprint = extractFingerprint(discover.toString());
        Files.writeString(ignoreFile, fingerprint + "\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
                new String[] {"--ignore-file", ignoreFile.toString(), jar.toString()},
                new PrintStream(out),
                new PrintStream(err),
                List.of(new R05EntitySetPosOffCoord()));

        assertThat(code).isEqualTo(0);
        assertThat(out.toString()).doesNotContain("\"ruleId\": \"R05\"");
        assertThat(out.toString()).contains("\"suppressed\": 1");
    }

    @Test
    void staleIgnoreEntryStillReportsTheFindingAndKeepsExitCodeNonZero() throws IOException {
        Path jar = writeJarWithBadR05Class();
        Path ignoreFile = tempDir.resolve("stale.multiforgeignore");
        Files.writeString(ignoreFile, "R05:com.example.mod.BadR05#teleportToBase(Ljava/lang/Object;)V#000000000000\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(
                new String[] {"--ignore-file", ignoreFile.toString(), jar.toString()},
                new PrintStream(out),
                new PrintStream(err),
                List.of(new R05EntitySetPosOffCoord()));

        assertThat(code).isEqualTo(1);
        assertThat(out.toString()).contains("\"ruleId\": \"R05\"");
        assertThat(out.toString()).contains("\"staleSuppressions\": 1");
    }

    private static String extractFingerprint(String json) {
        int idx = json.indexOf("\"fingerprint\": \"");
        int start = idx + "\"fingerprint\": \"".length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    @Test
    void emitsSarifWhenRequested() throws IOException {
        Path jar = writeJarWithBadR05Class();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = Main.run(
                new String[] {"--sarif", jar.toString()},
                new PrintStream(out),
                new PrintStream(err),
                List.of(new R05EntitySetPosOffCoord()));

        assertThat(code).isEqualTo(1);
        assertThat(out.toString()).contains("\"version\": \"2.1.0\"");
    }

    private Path writeJarWithBadR05Class() throws IOException {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR05", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "teleportToBase", false);
        Bytecode.invokeVirtual(mv, "net/minecraft/world/entity/Entity", "setPos", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        Path jar = tempDir.resolve("bad.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("com/example/mod/BadR05.class"));
            jos.write(bytes);
            jos.closeEntry();
        }
        return jar;
    }

    private Path writeEmptyJar() throws IOException {
        ClassWriter cw = Bytecode.newClass("com/example/mod/Clean", false);
        byte[] bytes = Bytecode.finish(cw);

        Path jar = tempDir.resolve("clean.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("com/example/mod/Clean.class"));
            jos.write(bytes);
            jos.closeEntry();
        }
        return jar;
    }
}
