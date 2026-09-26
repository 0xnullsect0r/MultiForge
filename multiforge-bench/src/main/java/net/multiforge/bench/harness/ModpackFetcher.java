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
 * Downloads (or copies), verifies and unpacks a modpack server zip for
 * {@link Atm10Bench}.
 *
 * <p>There is no built-in default pack URL: CurseForge file downloads need
 * an API key and change with every pack release, so the operator passes the
 * server-pack zip's URL (or a local path) with its SHA-256. A digest is
 * required for any network source — a bench that silently measured whatever
 * a URL served that day would not be reproducible. Unpacked packs usually
 * nest their server files one directory down; {@link #findServerRoot} finds
 * the directory that holds {@code mods/}.
 */
public final class ModpackFetcher {

    private static final String ZIP_FILE_NAME = "atm10-server-pack.zip";
    private static final String EXTRACT_DIR_NAME = "atm10";

    private ModpackFetcher() {}

    /**
     * Ensure the pack from {@code sourceUrl} is downloaded to {@code
     * buildDir/modpack/} and unzipped to {@code buildDir/modpack/atm10/}.
     * When {@code expectedSha256} is given, the zip is verified against it and
     * an existing download is reused only while it still matches; without one
     * (only allowed for {@code file:} sources) an existing non-empty extract
     * is reused as is.
     *
     * @param sourceUrl an {@code http(s):} URL, or a {@code file:} URI
     * @return the extracted pack directory
     */
    public static Path ensureDownloaded(Path buildDir, String sourceUrl, String expectedSha256) throws IOException {
        URI source = URI.create(sourceUrl);
        if (expectedSha256 == null && !"file".equalsIgnoreCase(source.getScheme())) {
            throw new IOException("a SHA-256 is required for a network modpack source: " + sourceUrl);
        }
        Path modpackDir = buildDir.resolve("modpack");
        Files.createDirectories(modpackDir);
        Path zip = modpackDir.resolve(ZIP_FILE_NAME);
        Path extractDir = modpackDir.resolve(EXTRACT_DIR_NAME);

        if (isAlreadyGood(zip, extractDir, expectedSha256)) {
            return extractDir;
        }

        download(source, zip);

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

    /**
     * The directory under {@code extracted} that holds {@code mods/}: {@code
     * extracted} itself, or the first match up to two levels down.
     */
    public static Path findServerRoot(Path extracted) throws IOException {
        if (Files.isDirectory(extracted.resolve("mods"))) return extracted;
        try (var walk = Files.walk(extracted, 3)) {
            return walk.filter(p ->
                            Files.isDirectory(p) && p.getFileName().toString().equals("mods"))
                    .map(Path::getParent)
                    .sorted(java.util.Comparator.comparingInt(Path::getNameCount))
                    .findFirst()
                    .orElseThrow(() -> new IOException("no mods/ directory in the modpack under " + extracted));
        }
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
