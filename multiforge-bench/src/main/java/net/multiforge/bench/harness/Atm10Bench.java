/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.bench.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Entry point for {@code :multiforge-bench:atm10} — the Phase 7.4 ATM10
 * modpack bench profile.
 *
 * <p>ATM10 is a ~500 MB / ~500-mod modpack. Downloading it as part of a
 * bench task would be expensive and fragile (network flake, CurseForge
 * auth walls, license terms on redistribution), so this task never
 * fetches it itself. Instead it takes a user-provided, already-prepared
 * ATM10-formatted server directory via {@code -PmodpackDir=<path>} — one
 * containing {@code mods/} and (optionally) {@code config/}
 * subdirectories, e.g. the unzipped server pack — copies those into a
 * fresh MultiForge dev-server run directory, boots, and runs the same
 * sprint-based MSPT capture {@link VanillaBench} uses.
 *
 * <p>Without {@code -PmodpackDir}, this prints setup instructions and
 * exits 0 — it is not an error to run {@code :atm10} without a modpack
 * on hand, it just can't do anything real yet.
 */
public final class Atm10Bench {

    private static final String HELP_MESSAGE =
            """
            Atm10Bench: no modpack directory given.

            Point this task at a prepared ATM10-formatted server directory
            (one containing a mods/ subdirectory, and usually a config/
            subdirectory too — e.g. the unzipped server pack from the ATM10
            CurseForge/Modrinth release) via:

              ./gradlew :multiforge-bench:atm10 -PmodpackDir=/path/to/atm10-server

            Optional: -Pticks=<n> (default 12000 = 10 game-minutes) and
            -Pworkers=<n> (default 4).

            See docs/design/m9-phase7-runbook.md §5 for the full Phase 7.4
            bench-harness scope and pass criteria. This task intentionally
            does not download the ATM10 pack itself — that is fragile and
            expensive to do inside a bench task, so it is left as an
            operator-provided input.
            """;

    public static void main(String[] args) throws Exception {
        String modpackDirProp = System.getProperty("bench.modpackDir", "").trim();
        if (modpackDirProp.isEmpty()) {
            System.out.println(HELP_MESSAGE);
            return;
        }

        Path modpackDir = Path.of(modpackDirProp);
        if (!Files.isDirectory(modpackDir)) {
            System.out.println("Atm10Bench: -PmodpackDir=" + modpackDirProp
                    + " does not exist or is not a directory.\n\n" + HELP_MESSAGE);
            return;
        }
        Path modsDir = modpackDir.resolve("mods");
        Path configDir = modpackDir.resolve("config");
        if (!Files.isDirectory(modsDir)) {
            System.out.println("Atm10Bench: expected a mods/ subdirectory under " + modpackDir
                    + " — is this an ATM10-formatted server dir?\n\n" + HELP_MESSAGE);
            return;
        }

        long ticks = Long.getLong("bench.ticks", 12000L);
        int workers = Integer.getInteger("bench.workers", 4);
        Path workspaceDir = Path.of(System.getProperty("bench.workspaceDir", "upstream/neoforge-1.21.1"));
        Path outputFile =
                Path.of(System.getProperty("bench.outputFile", "docs/verification/m9/7.4/atm10/patched.json"));
        Path bootLog = Path.of(System.getProperty("bench.bootLog", "multiforge-bench/build/bench-logs/atm10-boot.log"));

        long modJarCount;
        try (Stream<Path> s = Files.list(modsDir)) {
            modJarCount = s.filter(p -> p.toString().endsWith(".jar")).count();
        }
        System.out.println("Atm10Bench: modpackDir=" + modpackDir.toAbsolutePath() + " (" + modJarCount
                + " mod jars) workers=" + workers + " ticks=" + ticks);

        HeadlessServerRunner.Config config = new HeadlessServerRunner.Config(
                workspaceDir, workers, "1234567890", 20, modsDir, Files.isDirectory(configDir) ? configDir : null, "");
        MetricsCollector metrics = new MetricsCollector();
        Instant start = Instant.now();

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("modpack_dir", modpackDir.toAbsolutePath().toString());
        extra.put("mod_jar_count", modJarCount);

        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, bootLog)) {
            boolean bootOk = runner.boot(metrics);
            if (!bootOk) {
                System.err.println(
                        "Atm10Bench: server failed to boot within timeout with the given modpack — see " + bootLog);
                BenchResult failure =
                        BenchResult.from("atm10", workers, ticks, elapsedMs(start), metrics, 0, false, false, extra);
                failure.writeTo(outputFile);
                System.exit(1);
                return;
            }

            runner.runSprintProfile(ticks);
            boolean cleanStop = runner.shutdown(Duration.ofSeconds(3));

            BenchResult result = BenchResult.from(
                    "atm10", workers, ticks, elapsedMs(start), metrics, runner.heapPeakMb(), true, cleanStop, extra);
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            result.writeTo(outputFile);
            System.out.println(result.toJson());
            System.out.println("Atm10Bench: wrote " + outputFile.toAbsolutePath());
        }
    }

    private static long elapsedMs(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    private Atm10Bench() {}
}
