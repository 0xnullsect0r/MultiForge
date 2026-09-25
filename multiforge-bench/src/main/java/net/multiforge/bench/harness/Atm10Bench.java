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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Entry point for {@code :multiforge-bench:atm10} — a large-modpack MSPT
 * profile (named for All the Mods 10, the pack the milestone gate names).
 *
 * <p>The pack comes from either
 *
 * <ul>
 *   <li>{@code bench.modpackDir} — an unpacked server pack (a directory
 *       holding {@code mods/} and usually {@code config/}), or</li>
 *   <li>{@code bench.modpackUrl} plus {@code bench.modpackSha256} — a server
 *       pack zip fetched, verified and unpacked by {@link ModpackFetcher}
 *       into {@code bench.cacheDir} (reused while the digest matches).</li>
 * </ul>
 *
 * <p>Its {@code mods/} and {@code config/} are copied into the installed
 * server ({@link BenchSetup}), which boots and sprints {@code bench.ticks}
 * ticks like {@link VanillaBench}. With no pack given, this prints how to
 * supply one and exits with status 2: a bench that measured nothing must not
 * look like a passing run.
 */
public final class Atm10Bench {

    private static final String HELP_MESSAGE =
            """
            Atm10Bench: no modpack given. Either point at an unpacked server pack:

              ./gradlew :multiforge-bench:atm10 -PmodpackDir=/path/to/server-pack

            or at a server-pack zip and its SHA-256 (downloaded once, verified):

              ./gradlew :multiforge-bench:atm10 -PmodpackUrl=https://.../ServerFiles.zip \\
                  -PmodpackSha256=<hex>

            Optional: -Pticks=<n> (default 12000), -Pworkers=<n> (default 4),
            -Pserver=stock for the plain NeoForge control.
            """;

    public static void main(String[] args) throws Exception {
        Path modpackDir;
        try {
            modpackDir = resolveModpack();
        } catch (IOException e) {
            System.err.println("Atm10Bench: " + e.getMessage() + "\n\n" + HELP_MESSAGE);
            System.exit(2);
            return;
        }
        if (modpackDir == null) {
            System.err.println(HELP_MESSAGE);
            System.exit(2);
            return;
        }
        Path modsDir = modpackDir.resolve("mods");
        Path configDir = modpackDir.resolve("config");

        long ticks = Long.getLong("bench.ticks", 12000L);
        int workers = Integer.getInteger("bench.workers", 4);
        String flavour = BenchSetup.flavour();
        Path outputFile = Path.of(System.getProperty(
                "bench.outputFile",
                "docs/verification/m9/7.4/atm10/" + (flavour.equals("stock") ? "baseline" : "patched") + ".json"));
        Path bootLog = Path.of(System.getProperty(
                "bench.bootLog", "multiforge-bench/build/bench-logs/atm10-" + flavour + "-boot.log"));

        long modJarCount;
        try (Stream<Path> s = Files.list(modsDir)) {
            modJarCount = s.filter(p -> p.toString().endsWith(".jar")).count();
        }
        System.out.println("Atm10Bench: modpack=" + modpackDir.toAbsolutePath() + " (" + modJarCount
                + " mod jars) server=" + flavour + " workers=" + workers + " ticks=" + ticks);

        HeadlessServerRunner.Config config = HeadlessServerRunner.Config.of(BenchSetup.install(), workers, "1234567890")
                .withMods(modsDir, Files.isDirectory(configDir) ? configDir : null);
        MetricsCollector metrics = new MetricsCollector();
        Instant start = Instant.now();

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("server", flavour);
        extra.put("pacing", "sprint");
        extra.put("modpack", modpackDir.toAbsolutePath().toString());
        extra.put("mod_jar_count", modJarCount);

        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, bootLog)) {
            if (!runner.boot(metrics)) {
                System.err.println(
                        "Atm10Bench: server failed to boot within timeout with the given modpack — see " + bootLog);
                BenchResult.from("atm10", workers, ticks, elapsedMs(start), metrics, 0, false, false, extra)
                        .writeTo(outputFile);
                System.exit(1);
                return;
            }

            runner.runSprintProfile(ticks);
            boolean cleanStop = runner.shutdown(Duration.ofSeconds(3));

            BenchResult result = BenchResult.from(
                    "atm10", workers, ticks, elapsedMs(start), metrics, runner.rssPeakMb(), true, cleanStop, extra);
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            result.writeTo(outputFile);
            System.out.println(result.toJson());
            System.out.println("Atm10Bench: wrote " + outputFile.toAbsolutePath());
        }
    }

    /** The unpacked pack's server root, or null when neither property is set. */
    private static Path resolveModpack() throws IOException {
        String dir = System.getProperty("bench.modpackDir", "").trim();
        if (!dir.isEmpty()) {
            Path p = Path.of(dir);
            if (!Files.isDirectory(p)) throw new IOException("modpackDir " + dir + " is not a directory");
            return ModpackFetcher.findServerRoot(p);
        }
        String url = System.getProperty("bench.modpackUrl", "").trim();
        if (url.isEmpty()) return null;
        String sha = System.getProperty("bench.modpackSha256", "").trim();
        Path cache = Path.of(System.getProperty("bench.cacheDir", "multiforge-bench/build"));
        String source = url.contains("://") ? url : Path.of(url).toUri().toString();
        return ModpackFetcher.findServerRoot(
                ModpackFetcher.ensureDownloaded(cache, source, sha.isEmpty() ? null : sha));
    }

    private static long elapsedMs(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    private Atm10Bench() {}
}
