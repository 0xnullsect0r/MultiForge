/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.bench.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModpackFetcherTest {

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty(ModpackFetcher.MODPACK_URL_PROPERTY);
    }

    @Test
    void ensureDownloadedFetchesAndUnzipsAFileUrl(@TempDir Path tmp) throws IOException {
        Path buildDir = tmp.resolve("build");
        Path sourceZip = tmp.resolve("source.zip");
        writeZip(sourceZip, "mods/example.jar", "fake jar contents");

        Path extractDir =
                ModpackFetcher.ensureDownloaded(buildDir, sourceZip.toUri().toString(), null);

        assertThat(extractDir).isEqualTo(buildDir.resolve("modpack/atm10"));
        assertThat(extractDir.resolve("mods/example.jar")).exists();
        assertThat(Files.readString(extractDir.resolve("mods/example.jar"))).isEqualTo("fake jar contents");
    }

    @Test
    void ensureDownloadedIsIdempotentOnceExtracted(@TempDir Path tmp) throws IOException {
        Path buildDir = tmp.resolve("build");
        Path sourceZip = tmp.resolve("source.zip");
        writeZip(sourceZip, "mods/example.jar", "v1");

        Path first = ModpackFetcher.ensureDownloaded(buildDir, sourceZip.toUri().toString(), null);
        assertThat(Files.readString(first.resolve("mods/example.jar"))).isEqualTo("v1");

        // Overwrite the source zip with different contents; without a sha256 to force
        // re-verification, the already-extracted directory should be left alone.
        writeZip(sourceZip, "mods/example.jar", "v2-should-not-be-seen");
        Path second =
                ModpackFetcher.ensureDownloaded(buildDir, sourceZip.toUri().toString(), null);

        assertThat(Files.readString(second.resolve("mods/example.jar"))).isEqualTo("v1");
    }

    @Test
    void ensureDownloadedReDownloadsWhenShaNoLongerMatches(@TempDir Path tmp) throws IOException {
        Path buildDir = tmp.resolve("build");
        Path sourceZip = tmp.resolve("source.zip");
        writeZip(sourceZip, "mods/example.jar", "v1");
        String sha1 = ModpackFetcher.sha256Hex(sourceZip);

        Path first = ModpackFetcher.ensureDownloaded(buildDir, sourceZip.toUri().toString(), sha1);
        assertThat(Files.readString(first.resolve("mods/example.jar"))).isEqualTo("v1");

        writeZip(sourceZip, "mods/example.jar", "v2");
        String sha2 = ModpackFetcher.sha256Hex(sourceZip);
        assertThat(sha2).isNotEqualTo(sha1);

        Path second =
                ModpackFetcher.ensureDownloaded(buildDir, sourceZip.toUri().toString(), sha2);
        assertThat(Files.readString(second.resolve("mods/example.jar"))).isEqualTo("v2");
    }

    @Test
    void ensureDownloadedFailsOnShaMismatch(@TempDir Path tmp) throws IOException {
        Path buildDir = tmp.resolve("build");
        Path sourceZip = tmp.resolve("source.zip");
        writeZip(sourceZip, "mods/example.jar", "v1");

        assertThatThrownBy(() -> ModpackFetcher.ensureDownloaded(
                        buildDir, sourceZip.toUri().toString(), "deadbeef"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("sha256 mismatch");
    }

    @Test
    void resolveUrlPrefersSystemPropertyOverDefault() {
        System.setProperty(ModpackFetcher.MODPACK_URL_PROPERTY, "https://example.invalid/atm10.zip");
        assertThat(ModpackFetcher.resolveUrl()).isEqualTo("https://example.invalid/atm10.zip");
    }

    @Test
    void resolveUrlFallsBackToDefaultWhenUnset() {
        assertThat(ModpackFetcher.resolveUrl()).isEqualTo(ModpackFetcher.DEFAULT_MODPACK_URL);
    }

    private static void writeZip(Path zip, String entryName, String content) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
