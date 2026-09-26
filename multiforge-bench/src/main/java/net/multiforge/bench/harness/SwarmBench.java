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
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Entry point for {@code :multiforge-bench:swarm} — a headless player swarm
 * held for a real-time window, measuring whether the server keeps 20 TPS.
 *
 * <p><b>Bots (default, {@code bench.swarmMode=bots}).</b> {@link BotSwarm}
 * connects {@code bench.players} real protocol clients over the game port.
 * Once they are in, RCON makes them creative, gives them dirt, and spreads
 * them over a {@code bench.spread}-block radius around spawn so they land in
 * different regions; then they walk, place and break blocks for the run
 * duration. This exercises the whole player path: login and configuration,
 * chunk sending and tracking per player, movement handling, block
 * interaction and the resulting block and light updates, and entity
 * tracking between players.
 *
 * <p><b>Armor stands ({@code bench.swarmMode=armor-stand}).</b> The old
 * fallback, kept for machines that cannot afford the client-side decoding
 * cost of many bots: RCON {@code /summon}s tagged armor stands under a
 * forceload and teleports them in a random walk. No connection, chunk
 * sending or player tracking is exercised; the result says which mode ran.
 *
 * <p>The server runs at its natural pace (no {@code /tick sprint}) for
 * {@code bench.ticks / 20} seconds after the swarm is in place; timing comes
 * from {@code /multiforge tickstats} over exactly that window.
 */
public final class SwarmBench {

    private static final String SWARM_TAG = "mfbench_swarm";
    private static final String BOT_PREFIX = "mfbot";

    public static void main(String[] args) throws Exception {
        int players = Integer.getInteger("bench.players", 20);
        long ticks = Long.getLong("bench.ticks", 12000L);
        int workers = Integer.getInteger("bench.workers", Runtime.getRuntime().availableProcessors());
        int spread = Integer.getInteger("bench.spread", 512);
        int renderDistance = Integer.getInteger("bench.renderDistance", 8);
        String mode = System.getProperty("bench.swarmMode", "bots");
        Path outputFile = Path.of(
                System.getProperty("bench.outputFile", "docs/verification/m9/7.4/swarm-" + players + "/patched.json"));
        Path bootLog = Path.of(System.getProperty(
                "bench.bootLog", "multiforge-bench/build/bench-logs/swarm-" + players + "-boot.log"));
        String extraJvmArgs = System.getProperty("bench.extraJvmArgs", "");
        if (!mode.equals("bots") && !mode.equals("armor-stand")) {
            System.err.println("SwarmBench: bench.swarmMode must be bots or armor-stand, not " + mode);
            System.exit(2);
        }

        Duration churnDuration = Duration.ofMillis(ticks * 50L);
        System.out.println("SwarmBench: players=" + players + " mode=" + mode + " workers=" + workers + " duration="
                + churnDuration.toSeconds() + "s spread=" + spread);

        HeadlessServerRunner.Config config = HeadlessServerRunner.Config.of(BenchSetup.install(), workers, "1234567890")
                .withMaxPlayers(Math.max(players + 4, 20))
                .withExtraJvmArgs(extraJvmArgs)
                .withProperties(Map.of("allow-flight", "true", "view-distance", String.valueOf(renderDistance)));
        MetricsCollector metrics = new MetricsCollector();
        Instant start = Instant.now();

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("server", BenchSetup.flavour());
        extra.put("players", players);
        extra.put("swarm_mode", mode);
        extra.put("pacing", "real-time");

        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, bootLog)) {
            if (!runner.boot(metrics)) {
                System.err.println("SwarmBench: server failed to come up within timeout — see " + bootLog);
                BenchResult.from("swarm", workers, ticks, elapsedMs(start), metrics, 0, false, false, extra)
                        .writeTo(outputFile);
                System.exit(1);
                return;
            }

            boolean ok;
            if (mode.equals("bots")) {
                ok = runBots(runner, metrics, players, spread, renderDistance, churnDuration, extra);
            } else {
                spawnArmorStands(runner, players);
                runner.beginMeasurement();
                extra.put("churn_rounds", churnArmorStands(runner, churnDuration));
                runner.endMeasurement();
                despawnArmorStands(runner);
                ok = true;
            }

            if (runner.hasTickStats()) {
                ProbeSummary probes = ProbeSummary.parse(runner.rcon().command("multiforge probes"));
                long violations = probes.violations();
                long overruns = probes.overruns();
                extra.put("ownership_violations", violations);
                extra.put("region_overruns", overruns);
                extra.put("reroutes", probes.reroutes());
                extra.put("reroute_mismatches", probes.rerouteMismatches());
                if (violations > 0)
                    extra.put("violation_counters", probes.violationCounters().toString());
                if (Boolean.getBoolean("bench.failOnViolations") && (violations > 0 || overruns > 0)) ok = false;
            }
            boolean cleanStop = runner.shutdown(Duration.ofSeconds(2));
            if (Boolean.getBoolean("bench.failOnViolations") && !cleanStop) ok = false;
            BenchResult result = BenchResult.from(
                    "swarm", workers, ticks, elapsedMs(start), metrics, runner.rssPeakMb(), true, cleanStop, extra);
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            result.writeTo(outputFile);
            System.out.println(result.toJson());
            System.out.println("SwarmBench: wrote " + outputFile.toAbsolutePath());
            if (!ok) System.exit(1);
        }
    }

    /** @return false when bots failed to join or were dropped during the run */
    private static boolean runBots(
            HeadlessServerRunner runner,
            MetricsCollector metrics,
            int players,
            int spread,
            int renderDistance,
            Duration duration,
            Map<String, Object> extra)
            throws IOException, InterruptedException {
        try (BotSwarm swarm = new BotSwarm("127.0.0.1", runner.gamePort(), renderDistance)) {
            swarm.connect(BOT_PREFIX, players, 10);
            int joined = swarm.awaitJoined(players, 120_000 + players * 500L);
            System.out.println("SwarmBench: " + joined + "/" + players + " bots joined");
            extra.put("bots_joined", joined);
            if (joined < players) {
                extra.put("bot_disconnects", swarm.disconnects().toString());
                return false;
            }

            runner.rcon().command("gamemode creative @a");
            runner.rcon().command("give @a minecraft:dirt 64");
            if (spread > 0) {
                System.out.println("SwarmBench: "
                        + runner.rcon()
                                .command(String.format(
                                        Locale.ROOT,
                                        "spreadplayers 0 0 %d %d false @a",
                                        Math.max(8, spread / 8),
                                        spread)));
            }
            // Let the spread's chunk generation and sends settle before measuring.
            Thread.sleep(15_000);
            swarm.enableBlockWork();

            runner.beginMeasurement();
            long pollMs = Math.max(1000, Math.min(10_000, duration.toMillis() / 20));
            long deadline = System.currentTimeMillis() + duration.toMillis();
            int minConnected = players;
            while (System.currentTimeMillis() < deadline && runner.isAlive()) {
                Thread.sleep(pollMs);
                minConnected = Math.min(minConnected, swarm.connectedCount());
                runner.safeQuery();
            }
            runner.endMeasurement();

            extra.put("bots_connected_min", minConnected);
            extra.put("bots_connected_end", swarm.connectedCount());
            extra.put("blocks_placed", swarm.blocksPlaced());
            extra.put("blocks_broken", swarm.blocksBroken());
            extra.put("position_corrections", swarm.corrections());
            extra.put("regions", regionCount(runner));
            if (!swarm.disconnects().isEmpty())
                extra.put("bot_disconnects", swarm.disconnects().toString());
            System.out.println("SwarmBench: " + metrics.sampleCount() + " tick-query samples, "
                    + swarm.blocksPlaced() + " placements, " + swarm.blocksBroken() + " breaks, min connected "
                    + minConnected);
            return minConnected == players && runner.isAlive();
        }
    }

    /** Live region count from {@code /multiforge region list} (first line), or -1 on a stock server. */
    private static int regionCount(HeadlessServerRunner runner) {
        if (!runner.hasTickStats()) return -1;
        try {
            String reply = runner.rcon().command("multiforge region list");
            var m = java.util.regex.Pattern.compile("(\\d+) region").matcher(reply);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static void spawnArmorStands(HeadlessServerRunner runner, int players) throws IOException {
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
        System.out.println("SwarmBench: spawned " + players + " armor stands across a " + (2 * spreadRadius) + "x"
                + (2 * spreadRadius) + " block area, forceloaded at that footprint");
    }

    private static int churnArmorStands(HeadlessServerRunner runner, Duration duration) throws InterruptedException {
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
                runner.safeQuery();
            } catch (IOException e) {
                System.err.println("SwarmBench: churn round " + round + " RCON error: " + e.getMessage());
            }
            Thread.sleep(pollIntervalMs);
        }
        return round;
    }

    private static void despawnArmorStands(HeadlessServerRunner runner) {
        try {
            runner.rcon().command("kill @e[tag=" + SWARM_TAG + "]");
            runner.rcon().command("forceload remove all");
        } catch (IOException e) {
            System.err.println("SwarmBench: cleanup RCON error: " + e.getMessage());
        }
    }

    private static long elapsedMs(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
    }

    private SwarmBench() {}
}
