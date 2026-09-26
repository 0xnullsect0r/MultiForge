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
import java.util.List;
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
        Path outputFile = Path.of(System.getProperty(
                "bench.outputFile", "multiforge-bench/build/bench-results/swarm-" + players + ".json"));
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
        // Optional modpack (same properties as :atm10). Protocol bots are vanilla
        // clients, so with a pack that requires client mods use swarmMode=armor-stand.
        Path modpackDir = Atm10Bench.resolveModpack();
        if (modpackDir != null) {
            Path configDir = modpackDir.resolve("config");
            config = config.withMods(modpackDir.resolve("mods"), Files.isDirectory(configDir) ? configDir : null);
            System.out.println("SwarmBench: modpack=" + modpackDir.toAbsolutePath());
        }
        MetricsCollector metrics = new MetricsCollector();
        Instant start = Instant.now();

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("server", BenchSetup.flavour());
        extra.put("players", players);
        extra.put("swarm_mode", mode);
        extra.put("pacing", "real-time");
        if (modpackDir != null) extra.put("modpack", modpackDir.toAbsolutePath().toString());

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
                try {
                    ok = runBots(runner, metrics, players, spread, renderDistance, churnDuration, extra);
                } catch (IOException e) {
                    // The server died mid-run (RCON dropped): record that instead of
                    // leaving no result behind.
                    System.err.println("SwarmBench: lost the server mid-run: " + e);
                    extra.put("server_crashed", true);
                    extra.put(
                            "crash_phase",
                            extra.containsKey("bots_placed")
                                    ? "measurement"
                                    : extra.containsKey("bots_placement_started") ? "placement" : "join");
                    BenchResult.from(
                                    "swarm",
                                    workers,
                                    ticks,
                                    elapsedMs(start),
                                    metrics,
                                    runner.rssPeakMb(),
                                    true,
                                    false,
                                    extra)
                            .writeTo(outputFile);
                    System.out.println("SwarmBench: wrote " + outputFile.toAbsolutePath());
                    System.exit(1);
                    return;
                }
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
                extra.put(
                        "main_thread_chunk_loads", probes.counters().getOrDefault("region.main-thread-chunk-load", 0L));
                extra.put(
                        "wait_ms_main_thread",
                        probes.counters().getOrDefault("region-tick.wait-ms.main-thread-chunk-load", 0L));
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
            // Bots stand still until they are placed: hundreds of players walking at
            // the spawn point is quadratic entity-tracking work no server survives,
            // and not what a real server sees.
            swarm.setWalking(false);
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
                extra.put("bots_placed", placeBots(runner, swarm.names(), spread, renderDistance, extra));
            }
            // Let the spread's chunk generation and sends settle before measuring.
            Thread.sleep(15_000);
            swarm.setWalking(true);
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

    /**
     * Put each bot at a fixed spot on rings up to {@code spread} blocks from
     * spawn, standing on the surface: first a teleport high above the spot
     * (a player teleport loads its chunks), then, once its surroundings have
     * loaded, a drop onto the {@code motion_blocking} heightmap (which an
     * unloaded chunk cannot answer). One bot at a time, so terrain generation
     * never queues for every spot at once. A single {@code /spreadplayers} would generate every
     * candidate spot in one synchronous command and stall the server past
     * RCON's timeout at 20+ players.
     *
     * @return how many bots reached the surface of their spot
     */
    private static int placeBots(
            HeadlessServerRunner runner, List<String> names, int spread, int renderDistance, Map<String, Object> extra)
            throws IOException, InterruptedException {
        int placed = 0;
        for (int i = 0; i < names.size(); i++) {
            extra.put("bots_placement_started", i + 1);
            double angle = 2 * Math.PI * i / names.size();
            double r = spread * (0.35 + 0.65 * ((i * 7) % names.size()) / Math.max(1, names.size()));
            int[] spot = {(int) Math.round(Math.cos(angle) * r), (int) Math.round(Math.sin(angle) * r)};
            runner.rcon().command(String.format(Locale.ROOT, "tp %s %d 250 %d", names.get(i), spot[0], spot[1]));
            awaitSurroundingsLoaded(runner, spot, renderDistance);
            // Drop now, while the spot is loaded: the bots walk from the moment they
            // join, so a later pass finds most spots unloaded again.
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                String reply = runner.rcon()
                        .command(String.format(
                                Locale.ROOT,
                                "execute positioned %d 0 %d positioned over motion_blocking run tp %s ~ ~ ~",
                                spot[0],
                                spot[1],
                                names.get(i)));
                if (reply.startsWith("Teleported")) {
                    placed++;
                    break;
                }
                Thread.sleep(500);
            }
        }
        System.out.println("SwarmBench: placed " + placed + "/" + names.size() + " bots on rings up to " + spread
                + " blocks from spawn");
        return placed;
    }

    /**
     * Wait (up to a minute) until the chunks around a newly placed bot are
     * loaded, before the next bot is placed. Placing every bot at once into
     * fresh terrain queues thousands of chunks for generation together, and a
     * mob that looks one block into a still-generating neighbour then stalls
     * the tick behind that whole queue — on stock NeoForge as on MultiForge.
     * Players arriving over time never do that.
     */
    private static void awaitSurroundingsLoaded(HeadlessServerRunner runner, int[] spot, int renderDistance)
            throws IOException, InterruptedException {
        int d = Math.max(1, renderDistance - 2) * 16;
        int[][] probes = {{0, 0}, {d, 0}, {-d, 0}, {0, d}, {0, -d}};
        long deadline = System.currentTimeMillis() + 60_000;
        for (int[] o : probes) {
            String cmd = String.format(Locale.ROOT, "execute if loaded %d 0 %d", spot[0] + o[0], spot[1] + o[1]);
            while (!runner.rcon().command(cmd).contains("passed") && System.currentTimeMillis() < deadline) {
                Thread.sleep(250);
            }
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
