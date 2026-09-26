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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Resolves which server a bench profile runs against, from system properties
 * the Gradle tasks set:
 *
 * <ul>
 *   <li>{@code bench.server} — {@code multiforge} (default) or {@code stock}
 *       (plain NeoForge of the same version, the baseline);</li>
 *   <li>{@code bench.installer} — the MultiForge installer jar; default the
 *       newest {@code *-installer.jar} in {@code bench.forkLibs} (where
 *       {@code :neoforge:installerJar} writes it);</li>
 *   <li>{@code bench.stockInstaller} — a stock installer path or URL; default
 *       the NeoForge {@code bench.neoforgeVersion} installer from
 *       maven.neoforged.net, downloaded once into {@code bench.serverRoot};</li>
 *   <li>{@code bench.serverRoot} — where installs live, one directory per
 *       flavour.</li>
 * </ul>
 */
public final class BenchSetup {
    private BenchSetup() {}

    public static String flavour() {
        String f = System.getProperty("bench.server", "multiforge").trim();
        if (!f.equals("multiforge") && !f.equals("stock")) {
            throw new IllegalArgumentException("bench.server must be multiforge or stock, not " + f);
        }
        return f;
    }

    /** Install (or reuse) the server for {@link #flavour()}. */
    public static ServerInstall install() throws IOException, InterruptedException {
        return install(flavour());
    }

    /** Install (or reuse) the server for {@code flavour}: {@code multiforge} or {@code stock}. */
    public static ServerInstall install(String flavour) throws IOException, InterruptedException {
        Path root = Path.of(System.getProperty("bench.serverRoot", "multiforge-bench/build/server"));
        Path installer = flavour.equals("stock") ? stockInstaller(root) : multiforgeInstaller();
        System.out.println("bench: " + flavour + " server from " + installer);
        return ServerInstall.ensure(installer, root.resolve(flavour), root.resolve(flavour + "-install.log"));
    }

    private static Path multiforgeInstaller() throws IOException {
        String explicit = System.getProperty("bench.installer", "").trim();
        if (!explicit.isEmpty()) return Path.of(explicit);
        Path libs =
                Path.of(System.getProperty("bench.forkLibs", "upstream/neoforge-1.21.1/projects/neoforge/build/libs"));
        if (Files.isDirectory(libs)) {
            try (Stream<Path> s = Files.list(libs)) {
                var newest = s.filter(p -> p.getFileName().toString().endsWith("-installer.jar"))
                        .max(Comparator.comparingLong(BenchSetup::mtime));
                if (newest.isPresent()) return newest.get();
            }
        }
        throw new IOException("no MultiForge installer in " + libs + ": build it with `./gradlew "
                + ":neoforge:installerJar` in upstream/neoforge-1.21.1, or pass -Pinstaller=<jar>");
    }

    private static Path stockInstaller(Path root) throws IOException, InterruptedException {
        String version = System.getProperty("bench.neoforgeVersion", "21.1.251");
        String source = System.getProperty(
                "bench.stockInstaller",
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version + "/neoforge-" + version
                        + "-installer.jar");
        if (!source.startsWith("http")) return Path.of(source);
        Path cached = root.resolve("installers").resolve(source.substring(source.lastIndexOf('/') + 1));
        if (Files.isRegularFile(cached)) return cached;
        Files.createDirectories(cached.getParent());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        Path tmp = Files.createTempFile(cached.getParent(), "installer-", ".tmp");
        HttpResponse<Path> response = client.send(
                HttpRequest.newBuilder(URI.create(source))
                        .timeout(Duration.ofMinutes(5))
                        .build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() / 100 != 2) {
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + response.statusCode() + " fetching " + source);
        }
        Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
        return cached;
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
