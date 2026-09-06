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
 * Entry point for {@code :multiforge-bench:vanilla} — the Phase 7.4
 * vanilla-only TPS/MSPT baseline. Boots the current MultiForge fork with
 * {@code workers=1} and no mods on the classpath, sprints the configured
 * tick count, and writes the result JSON specified in
 * {@code docs/design/m9-phase7-runbook.md} §5.
 *
 * <p>System properties (set by the {@code vanilla} Gradle task from
 * {@code -Pticks=}):
 *
 * <ul>
 *   <li>{@code bench.ticks} — default {@code 12000} (10 game-minutes)</li>
 *   <li>{@code bench.workspaceDir} — default {@code upstream/neoforge-1.21.1}</li>
 *   <li>{@code bench.outputFile} — default {@code docs/verification/m9/7.4/vanilla/patched.json}</li>
 *   <li>{@code bench.bootLog} — where the child server's stdout/stderr is captured</li>
 * </ul>
 */
public final class VanillaBench {

    public static void main(String[] args) throws Exception {
        long ticks = Long.getLong("bench.ticks", 12000L);
        int workers = 1;
        Path workspaceDir = Path.of(System.getProperty("bench.workspaceDir", "upstream/neoforge-1.21.1"));
        Path outputFile =
                Path.of(System.getProperty("bench.outputFile", "docs/verification/m9/7.4/vanilla/patched.json"));
        Path bootLog =
                Path.of(System.getProperty("bench.bootLog", "multiforge-bench/build/bench-logs/vanilla-boot.log"));

        System.out.println("VanillaBench: workspaceDir=" + workspaceDir.toAbsolutePath() + " workers=" + workers
                + " ticks=" + ticks);

        HeadlessServerRunner.Config config =
                new HeadlessServerRunner.Config(workspaceDir, workers, "1234567890", 20, null, null, "");
        MetricsCollector metrics = new MetricsCollector();
        Instant start = Instant.now();

        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, bootLog)) {
            boolean bootOk = runner.boot(metrics);
            if (!bootOk) {
                System.err.println("VanillaBench: server failed to come up within timeout — see " + bootLog);
                BenchResult failure = BenchResult.from(
                        "vanilla", workers, ticks, elapsedMs(start), metrics, 0, false, false, Map.of());
                failure.writeTo(outputFile);
                System.exit(1);
                return;
            }

            runner.runSprintProfile(ticks);
            boolean cleanStop = runner.shutdown(Duration.ofSeconds(2));

            BenchResult result = BenchResult.from(
                    "vanilla",
                    workers,
                    ticks,
                    elapsedMs(start),
                    metrics,
                    runner.heapPeakMb(),
                    true,
                    cleanStop,
                    Map.of());
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
