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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Entry point for {@code :multiforge-bench:vanilla} — the no-mods MSPT
 * baseline. Boots an installed server with no mods ({@link BenchSetup}:
 * {@code bench.server=multiforge}, or {@code stock} for the plain NeoForge
 * control of the same version) at {@code bench.workers} workers, sprints
 * {@code bench.ticks} ticks, and writes the result JSON. The two flavours'
 * results side by side give the region runtime's overhead on an idle world.
 *
 * <p>System properties (set by the {@code vanilla} Gradle task):
 *
 * <ul>
 *   <li>{@code bench.ticks} — default {@code 12000} (10 game-minutes)</li>
 *   <li>{@code bench.workers} — default {@code 1}</li>
 *   <li>{@code bench.outputFile} — default {@code
 *       multiforge-bench/build/bench-results/vanilla-<flavour>.json}</li>
 *   <li>{@code bench.bootLog} — where the server's console output is captured</li>
 * </ul>
 */
public final class VanillaBench {

    public static void main(String[] args) throws Exception {
        long ticks = Long.getLong("bench.ticks", 12000L);
        int workers = Integer.getInteger("bench.workers", 1);
        String flavour = BenchSetup.flavour();
        Path outputFile = Path.of(System.getProperty(
                "bench.outputFile", "multiforge-bench/build/bench-results/vanilla-" + flavour + ".json"));
        Path bootLog = Path.of(System.getProperty(
                "bench.bootLog", "multiforge-bench/build/bench-logs/vanilla-" + flavour + "-boot.log"));

        System.out.println("VanillaBench: server=" + flavour + " workers=" + workers + " ticks=" + ticks);

        HeadlessServerRunner.Config config =
                HeadlessServerRunner.Config.of(BenchSetup.install(), workers, "1234567890");
        MetricsCollector metrics = new MetricsCollector();
        Instant start = Instant.now();
        Map<String, Object> extra = Map.of("server", flavour, "pacing", "sprint");

        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, bootLog)) {
            if (!runner.boot(metrics)) {
                System.err.println("VanillaBench: server failed to come up within timeout — see " + bootLog);
                BenchResult.from("vanilla", workers, ticks, elapsedMs(start), metrics, 0, false, false, extra)
                        .writeTo(outputFile);
                System.exit(1);
                return;
            }

            runner.runSprintProfile(ticks);
            boolean cleanStop = runner.shutdown(Duration.ofSeconds(2));

            BenchResult result = BenchResult.from(
                    "vanilla", workers, ticks, elapsedMs(start), metrics, runner.rssPeakMb(), true, cleanStop, extra);
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            result.writeTo(outputFile);
            System.out.println(result.toJson());
            System.out.println("VanillaBench: wrote " + outputFile.toAbsolutePath());
        }
    }

    private static long elapsedMs(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    private VanillaBench() {}
}
