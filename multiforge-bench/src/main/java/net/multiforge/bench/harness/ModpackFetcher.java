/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.bench.harness;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and unpacks the ATM10 server pack for {@code
 * :multiforge-bench:atm10}.
 *
 * <p><b>Note on {@link Atm10Bench}:</b> as of this writing {@code
 * Atm10Bench} deliberately does <i>not</i> call this class — its class
 * doc documents an explicit decision to require an operator-provided
 * {@code -PmodpackDir} instead of auto-fetching, citing network flake,
 * CurseForge auth walls, and pack-redistribution licensing terms. This
 * class exists as the auto-download building block for whoever revisits
 * that trade-off (e.g. a CI nightly run with its own hosting for the
 * pack); it is intentionally self-contained and does not change {@code
 * Atm10Bench}'s current behavior.
 *
 * <p><b>{@link #DEFAULT_MODPACK_URL}:</b> CurseForge file IDs are
 * per-upload and change every time the ATM10 modpack is updated, and
 * there is no stable canonical direct-download URL without hitting the
 * CurseForge API with a key (out of scope for a bench-harness default).
 * The constant below is therefore a documented placeholder, not a
 * verified live link. Point at a real pack instead via {@code
 * -PmodpackUrl=<url>} (see {@link #MODPACK_URL_PROPERTY}), or update the
 * constant once a stable mirror (e.g. a project-hosted Modrinth version
 * file URL, which — unlike CurseForge — allows a stable
 * "latest version of this project" API query) is chosen.
 */
public final class ModpackFetcher {

    /** {@code -PmodpackUrl=<url>} system property read by {@link #resolveUrl()}. */
    public static final String MODPACK_URL_PROPERTY = "modpackUrl";

    /**
     * Placeholder ATM10 server-pack URL — see class doc. Update this (or
     * pass {@value #MODPACK_URL_PROPERTY}) before relying on {@link
     * #ensureDownloaded(Path)} to fetch a real pack.
     */
    static final String DEFAULT_MODPACK_URL =
            "https://www.curseforge.com/api/v1/mods/UPDATE_ME_WITH_REAL_ATM10_FILE_ID/download-url";

    private static final String ZIP_FILE_NAME = "atm10-server-pack.zip";
    private static final String EXTRACT_DIR_NAME = "atm10";

    private ModpackFetcher() {}

    /** Resolves the modpack URL: {@code -DmodpackUrl}/{@code -PmodpackUrl} if set, else {@link #DEFAULT_MODPACK_URL}. */
    public static String resolveUrl() {
        String prop = System.getProperty(MODPACK_URL_PROPERTY);
        return (prop == null || prop.isBlank()) ? DEFAULT_MODPACK_URL : prop.trim();
    }

    /**
     * Ensures the ATM10 pack is downloaded to {@code buildDir/modpack/}
     * and unzipped to {@code buildDir/modpack/atm10/}, using {@link
     * #resolveUrl()} as the source. Idempotent: a second call with the
     * same inputs and an already-populated extract directory is a no-op.
     *
     * @return the extracted pack directory ({@code buildDir/modpack/atm10})
     */
    public static Path ensureDownloaded(Path buildDir) throws IOException {
        return ensureDownloaded(buildDir, resolveUrl(), null);
    }

    /**
     * Same as {@link #ensureDownloaded(Path)} but with an explicit
     * source URL and, optionally, an expected sha256 hex digest of the
     * downloaded zip. When {@code expectedSha256} is non-null, a
     * previously-downloaded zip is re-verified against it and only
     * skipped (both download and re-extraction) when it still matches —
     * this is the "sha256-verify, idempotent" contract C4.4 asks for.
     * When {@code expectedSha256} is {@code null}, idempotency instead
     * falls back to "zip and extract dir both already exist and the
     * extract dir is non-empty" — a real digest isn't known for every
     * caller (see {@link #DEFAULT_MODPACK_URL}'s placeholder status).
     *
     * @param sourceUrl any URL {@link HttpClient} — or, for tests, a
     *        {@code file://} URI via {@link URI#toURL()} — can fetch
     * @return the extracted pack directory
     */
    public static Path ensureDownloaded(Path buildDir, String sourceUrl, String expectedSha256) throws IOException {
        Path modpackDir = buildDir.resolve("modpack");
        Files.createDirectories(modpackDir);
        Path zip = modpackDir.resolve(ZIP_FILE_NAME);
        Path extractDir = modpackDir.resolve(EXTRACT_DIR_NAME);

        if (isAlreadyGood(zip, extractDir, expectedSha256)) {
            return extractDir;
        }

        download(URI.create(sourceUrl), zip);

        if (expectedSha256 != null) {
            String actual = sha256Hex(zip);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException(
                        "modpack download sha256 mismatch: expected " + expectedSha256 + " but got " + actual);
            }
        }

        deleteRecursively(extractDir);
        unzip(zip, extractDir);
        return extractDir;
    }

    private static boolean isAlreadyGood(Path zip, Path extractDir, String expectedSha256) throws IOException {
        if (!Files.isRegularFile(zip) || !Files.isDirectory(extractDir) || isEmptyDir(extractDir)) {
            return false;
        }
        if (expectedSha256 == null) {
            return true;
        }
        return sha256Hex(zip).equalsIgnoreCase(expectedSha256);
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    private static void download(URI uri, Path dest) throws IOException {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            // HttpClient does not support file:// — used by tests to avoid a real network fetch.
            Files.copy(Path.of(uri), dest, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        try {
            Path tmp = Files.createTempFile(dest.getParent(), "modpack-download-", ".tmp");
            HttpResponse<Path> response;
            try {
                // `tmp` already exists (created above); the default ofFile(Path) open
                // options are CREATE, WRITE, TRUNCATE_EXISTING, so this overwrites it in place.
                response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            if (response.statusCode() / 100 != 2) {
                Files.deleteIfExists(tmp);
                throw new IOException("modpack download failed: HTTP " + response.statusCode() + " from " + uri);
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("modpack download interrupted", e);
        }
    }

    private static void unzip(Path zip, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (InputStream fileIn = Files.newInputStream(zip);
                ZipInputStream zis = new ZipInputStream(fileIn)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    // Zip-slip guard: refuse an entry that would escape targetDir.
                    throw new IOException("modpack zip entry escapes target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available in this JDK", e);
        }
    }
}
