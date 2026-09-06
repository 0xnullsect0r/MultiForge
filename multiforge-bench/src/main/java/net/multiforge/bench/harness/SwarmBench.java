/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.bench.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Entry point for {@code :multiforge-bench:swarm} — the Phase 7.4
 * headless bot swarm at a configurable player count.
 *
 * <p><b>Simplified swarm (documented per the Phase 7.4a task brief):</b>
 * this does <em>not</em> open real Minecraft client connections. A
 * from-scratch handshake + login + play-packet client, or a
 * license-compatible off-the-shelf one, was judged too large a scope
 * addition for this pass (and would need its own license-compat
 * review per CLAUDE.md before landing). Instead this drives the
 * already-booted server over the same RCON channel {@code vanilla} and
 * {@code atm10} use:
 *
 * <ol>
 *   <li>{@code /summon minecraft:armor_stand} once per simulated player,
 *       scattered around spawn, tagged {@code mfbench_swarm};</li>
 *   <li>{@code /forceload add} over the scatter area, so the swarm's
 *       footprint stays chunk-loaded/tracked like a real player's render
 *       distance would keep it loaded;</li>
 *   <li>a real-time churn loop for the run duration: each round moves
 *       every bot with {@code execute as @e[tag=mfbench_swarm] at @s run
 *       tp @s ~dx ~ ~dz} (a genuine random-walk step per entity, one RCON
 *       round-trip regardless of player count) and periodically fires a
 *       {@code particle} burst per bot as a stand-in for "dig" activity;</li>
 *   <li>{@code /kill @e[tag=mfbench_swarm]} + {@code /forceload remove
 *       all} to tear the swarm back down before {@code stop}.</li>
 * </ol>
 *
 * <p>This exercises the chunk-load/chunk-tracking and per-entity tick
 * paths under N-way concurrent load without needing real player
 * connections — it does <em>not</em> exercise the packet-flush /
 * client-connection paths a real protocol client would (no client
 * sockets are ever opened against the server's game port). If a real
 * protocol client lands in a future pass, it should slot in as an
 * alternate {@code SwarmBench} mode behind the same {@code -Pplayers=}
 * flag.
 *
 * <p>Unlike {@link VanillaBench} and {@link Atm10Bench}, this profile
 * runs the server at its <em>natural</em> pace (no {@code /tick
 * sprint}) — the whole point of a swarm bench is whether sustained
 * concurrent load holds 20 TPS over real time, which a sprint (no
 * pacing) can't measure.
 */
public final class SwarmBench {

    private static final String SWARM_TAG = "mfbench_swarm";

    public static void main(String[] args) throws Exception {
        int players = Integer.getInteger("bench.players", 20);
        long ticks = Long.getLong("bench.ticks", 12000L);
        Path workspaceDir = Path.of(System.getProperty("bench.workspaceDir", "upstream/neoforge-1.21.1"));
        Path outputFile = Path.of(
                System.getProperty("bench.outputFile", "docs/verification/m9/7.4/swarm-" + players + "/patched.json"));
        Path bootLog = Path.of(System.getProperty(
                "bench.bootLog", "multiforge-bench/build/bench-logs/swarm-" + players + "-boot.log"));
        // Phase X task X.8 (docs/verification/m456/x8-strict-mode-swarm.md)
        // needs the nested `:neoforge:runServer` launch to carry
        // -Dmultiforge.regiontick.strict=on so the strict-mode watchdog is
        // actually armed for the run — nothing plumbed extraJvmArgs through
        // to this Config field until now (VanillaBench/Atm10Bench still
        // hardcode ""). See HeadlessServerRunner.Config#extraJvmArgs.
        String extraJvmArgs = System.getProperty("bench.extraJvmArgs", "");

        // ticks/20 mirrors the "N ticks at the natural 20 TPS target"
        // framing the other two profiles use for -Pticks, but here it is
        // an actual real-time wall-clock duration, not a sprint count.
        Duration churnDuration = Duration.ofMillis(ticks * 50L);

        System.out.println("SwarmBench: players=" + players + " duration=" + churnDuration.toSeconds()
                + "s (real-time; simplified RCON /summon armor-stand swarm — see multiforge-bench/README.md)");

        HeadlessServerRunner.Config config = new HeadlessServerRunner.Config(
                workspaceDir, 1, "1234567890", Math.max(players + 4, 20), null, null, extraJvmArgs);
        MetricsCollector metrics = new MetricsCollector();
        Instant start = Instant.now();

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("players", players);
        extra.put("swarm_mode", "rcon-armor-stand-fallback");

        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, bootLog)) {
            boolean bootOk = runner.boot(metrics);
            if (!bootOk) {
                System.err.println("SwarmBench: server failed to come up within timeout — see " + bootLog);
                BenchResult failure =
                        BenchResult.from("swarm", 1, ticks, elapsedMs(start), metrics, 0, false, false, extra);
                failure.writeTo(outputFile);
                System.exit(1);
                return;
            }

            spawnSwarm(runner, players);
            int rounds = churnSwarm(runner, metrics, churnDuration);
            extra.put("churn_rounds", rounds);
            despawnSwarm(runner);

            boolean cleanStop = runner.shutdown(Duration.ofSeconds(2));
            BenchResult result = BenchResult.from(
                    "swarm", 1, ticks, elapsedMs(start), metrics, runner.heapPeakMb(), true, cleanStop, extra);
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            result.writeTo(outputFile);
            System.out.println(result.toJson());
            System.out.println("SwarmBench: wrote " + outputFile.toAbsolutePath());
        }
    }

    private static void spawnSwarm(HeadlessServerRunner runner, int players) throws IOException {
        int side = (int) Math.ceil(Math.sqrt(Math.max(1, players)));
        int spreadRadius = Math.max(16, side * 8);
        runner.rcon()
                .command("forceload add " + (-spreadRadius) + " " + (-spreadRadius) + " " + spreadRadius + " "
                        + spreadRadius);

        Random rnd = new Random(7);
        for (int i = 0; i < players; i++) {
            int x = rnd.nextInt(2 * spreadRadius + 1) - spreadRadius;
            int z = rnd.nextInt(2 * spreadRadius + 1) - spreadRadius;
            String cmd = String.format(
                    Locale.ROOT, "summon minecraft:armor_stand %d 200 %d {Tags:[\"%s\"],Silent:1b}", x, z, SWARM_TAG);
            runner.rcon().command(cmd);
        }
        System.out.println("SwarmBench: spawned " + players + " bots across a " + (2 * spreadRadius) + "x"
                + (2 * spreadRadius) + " block area, forceloaded at that footprint");
    }

    /** Real-time random-walk + occasional "dig" (particle burst) loop, polling {@code /tick query} every round. */
    private static int churnSwarm(HeadlessServerRunner runner, MetricsCollector metrics, Duration duration)
            throws InterruptedException {
        long pollIntervalMs = Math.max(500, Math.min(3000, duration.toMillis() / 10));
        long deadline = System.currentTimeMillis() + duration.toMillis();
        Random rnd = new Random(42);
        int round = 0;
        while (System.currentTimeMillis() < deadline) {
            round++;
            int dx = rnd.nextInt(7) - 3;
            int dz = rnd.nextInt(7) - 3;
            try {
                runner.rcon().command("execute as @e[tag=" + SWARM_TAG + "] at @s run tp @s ~" + dx + " ~ ~" + dz);
                if (round % 3 == 0) {
                    runner.rcon()
                            .command("execute as @e[tag=" + SWARM_TAG + "] at @s run particle "
                                    + "minecraft:block minecraft:dirt ~ ~-1 ~ 0.2 0 0.2 0 3");
                }
                runner.safeQuery();
            } catch (IOException e) {
                System.err.println("SwarmBench: churn round " + round + " RCON error: " + e.getMessage());
            }
            Thread.sleep(pollIntervalMs);
        }
        System.out.println("SwarmBench: churn complete after " + round + " rounds, " + metrics.sampleCount()
                + " tick-query samples");
        return round;
    }

    private static void despawnSwarm(HeadlessServerRunner runner) {
        try {
            runner.rcon().command("kill @e[tag=" + SWARM_TAG + "]");
            runner.rcon().command("forceload remove all");
        } catch (IOException e) {
            System.err.println(
                    "SwarmBench: cleanup RCON error (non-fatal, server is about to stop anyway): " + e.getMessage());
        }
    }

    private static long elapsedMs(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    private SwarmBench() {}
}
