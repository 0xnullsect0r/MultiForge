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
                setRegionSize(runner, extra);
                spawnArmorStands(runner, players);
                spawnMobs(runner, players, Integer.getInteger("bench.mobs", 0));
                int[] far = forceloadFarArea(runner, extra);
                if (far != null) {
                    // Let the far area load and its region settle before measuring.
                    Thread.sleep(10_000L);
                    command(runner, "multiforge chunkcost on");
                }
                runner.beginMeasurement();
                extra.put("churn_rounds", churnArmorStands(runner, churnDuration));
                runner.endMeasurement();
                if (far != null) reportFarArea(runner, far, extra);
                despawnArmorStands(runner);
                ok = true;
            }

            if (!runner.isAlive()) {
                // Died at the end of the run; there is nothing left to query.
                System.err.println("SwarmBench: the server died during the run — see " + bootLog);
                extra.put("server_crashed", true);
                extra.put("crash_phase", "measurement");
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
            if (runner.hasTickStats()) {
                ProbeSummary probes =
                        ProbeSummary.collect(prefix -> runner.rcon().command(("multiforge probes " + prefix).strip()));
                String probeDump = probes.render();
                // The whole dump next to the result, for the serial-lane breakdown
                // (event.dispatch.serial.event.* / .mod.* / .world.*).
                Path probeFile = outputFile.resolveSibling(outputFile.getFileName() + ".probes.txt");
                Files.createDirectories(probeFile.toAbsolutePath().getParent());
                Files.writeString(probeFile, probeDump);
                for (String key : List.of(
                        "serial-lane.handoff",
                        "serial-lane.inline",
                        "event.dispatch.serial",
                        "event.dispatch.serial-post",
                        "region-tick.inline.single",
                        "region-tick.inline.hot",
                        "region-tick.hot",
                        "region-tick.hot-released",
                        "region-tick.wait-ns.serial-lane",
                        "region-tick.waits.serial-lane")) {
                    extra.put(
                            key.replace('.', '_').replace('-', '_'),
                            probes.counters().getOrDefault(key, 0L));
                }
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
            int waterMobs = Integer.getInteger("bench.waterMobs", 0);
            if (waterMobs > 0) buildWaterCrowd(runner, swarm.names().get(0), waterMobs, extra);
            // Let the spread's chunk generation and sends settle before measuring.
            Thread.sleep(15_000);
            swarm.setWalking(true);
            swarm.enableBlockWork();

            runner.beginMeasurement();
            long pollMs = Math.max(1000, Math.min(10_000, duration.toMillis() / 20));
            long deadline = System.currentTimeMillis() + duration.toMillis();
            int minConnected = players;
            RegionMsptSamples regionMspt = new RegionMsptSamples();
            while (System.currentTimeMillis() < deadline && runner.isAlive()) {
                Thread.sleep(pollMs);
                minConnected = Math.min(minConnected, swarm.connectedCount());
                runner.safeQuery();
                if (runner.hasTickStats()) regionMspt.sample(command(runner, "multiforge region list"));
            }
            runner.endMeasurement();
            regionMspt.report(extra);
            if (waterMobs > 0) extra.put("water_mobs_end", countWaterMobs(runner));

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

    /** The water crowd's mobs, in rotation. */
    private static final String[] WATER_MOBS = {"squid", "glow_squid", "cod"};

    /**
     * {@code bench.waterMobs}: the v1.11 incident's crowd. A glass basin of water
     * (48x48, 6 deep, at y 150) {@code bench.waterOffset} blocks (default 24) east of
     * the first bot, forceloaded, filled with that many persistent squid, glow squid
     * and cod. Entity cramming is turned off ({@code maxEntityCramming 0}) so the crowd
     * keeps its size; the pushing between the mobs, the cost that grows with the crowd,
     * still runs.
     */
    private static void buildWaterCrowd(HeadlessServerRunner runner, String bot, int mobs, Map<String, Object> extra)
            throws IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(-?[0-9.]+)d, (-?[0-9.]+)d, (-?[0-9.]+)d\\]")
                .matcher(runner.rcon().command("data get entity " + bot + " Pos"));
        int bx = 0, bz = 0;
        if (m.find()) {
            bx = (int) Math.floor(Double.parseDouble(m.group(1)));
            bz = (int) Math.floor(Double.parseDouble(m.group(3)));
        }
        int offset = Integer.getInteger("bench.waterOffset", 24);
        int x0 = bx + offset, x1 = x0 + 49, z0 = bz - 25, z1 = z0 + 49, y0 = 150, y1 = 157;
        command(runner, "gamerule maxEntityCramming 0");
        command(runner, "gamerule doMobSpawning false");
        command(runner, String.format(Locale.ROOT, "forceload add %d %d %d %d", x0, z0, x1, z1));
        long deadline = System.currentTimeMillis() + 60_000;
        String probe = String.format(Locale.ROOT, "execute if block %d -64 %d minecraft:bedrock", x1, z1);
        while (System.currentTimeMillis() < deadline
                && !runner.rcon().command(probe).contains("passed")) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        command(
                runner,
                String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass hollow", x0, y0, z0, x1, y1, z1));
        command(
                runner,
                String.format(
                        Locale.ROOT,
                        "fill %d %d %d %d %d %d minecraft:water",
                        x0 + 1,
                        y0 + 1,
                        z0 + 1,
                        x1 - 1,
                        y1 - 1,
                        z1 - 1));
        Random rnd = new Random(23);
        for (int i = 0; i < mobs; i++) {
            runner.rcon()
                    .command(String.format(
                            Locale.ROOT,
                            "summon minecraft:%s %d.5 %d.5 %d.5 {PersistenceRequired:1b}",
                            WATER_MOBS[i % WATER_MOBS.length],
                            x0 + 1 + rnd.nextInt(48),
                            y0 + 1 + rnd.nextInt(6),
                            z0 + 1 + rnd.nextInt(48)));
        }
        extra.put("water_mobs", mobs);
        extra.put("water_basin", x0 + "," + z0 + ".." + x1 + "," + z1 + " (bot at " + bx + "," + bz + ")");
        extra.put("water_mobs_start", countWaterMobs(runner));
        System.out.println("SwarmBench: water crowd of " + mobs + " in the basin at " + extra.get("water_basin"));
    }

    private static int countWaterMobs(HeadlessServerRunner runner) {
        int n = 0;
        for (String t : WATER_MOBS) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("count: (\\d+)")
                    .matcher(command(runner, "execute if entity @e[type=minecraft:" + t + "]"));
            if (m.find()) n += Integer.parseInt(m.group(1));
        }
        return n;
    }

    /**
     * The busiest region's tick time, from {@code /multiforge region list} (each
     * region's p50/p95/p99 over the last 5 s) sampled through the window: the
     * median of the samples' p50s and p99s, and the largest p99.
     */
    static final class RegionMsptSamples {
        private static final java.util.regex.Pattern COST =
                java.util.regex.Pattern.compile("tick ([0-9.]+)/([0-9.]+)/([0-9.]+) ms \\(p50/p95/p99");
        private final List<Double> p50 = new java.util.ArrayList<>();
        private final List<Double> p99 = new java.util.ArrayList<>();

        void sample(String regionList) {
            double best50 = -1, best99 = -1;
            java.util.regex.Matcher m = COST.matcher(regionList);
            while (m.find()) {
                double a = Double.parseDouble(m.group(1));
                double c = Double.parseDouble(m.group(3));
                if (a > best50) {
                    best50 = a;
                    best99 = c;
                }
            }
            if (best50 >= 0) {
                p50.add(best50);
                p99.add(best99);
            }
        }

        void report(Map<String, Object> extra) {
            if (p50.isEmpty()) return;
            extra.put("region_mspt_samples", p50.size());
            extra.put("region_mspt_p50", median(p50));
            extra.put("region_mspt_p99", median(p99));
            extra.put(
                    "region_mspt_p99_max",
                    p99.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        }

        static double median(List<Double> values) {
            List<Double> sorted = new java.util.ArrayList<>(values);
            java.util.Collections.sort(sorted);
            int n = sorted.size();
            return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        }
    }

    /** {@code bench.regionSize} (chunks): {@code /multiforge region size} right after boot, before any forceload. */
    private static void setRegionSize(HeadlessServerRunner runner, Map<String, Object> extra) {
        String size = System.getProperty("bench.regionSize");
        if (size == null || !runner.hasTickStats()) return;
        extra.put("region_size_chunks", Integer.parseInt(size));
        extra.put(
                "region_size_reply",
                command(runner, "multiforge region size " + size).strip());
    }

    /**
     * {@code bench.farArea=x,z} (block coordinates): forceload a 5x5-chunk area
     * there with nothing in it — the wilderness around a lone player far from
     * the busy forceloaded area at spawn (the user report: a player ~2.4k blocks
     * from a base, sections merged diagonally). Returns its centre chunk.
     */
    private static int[] forceloadFarArea(HeadlessServerRunner runner, Map<String, Object> extra) {
        String spec = System.getProperty("bench.farArea");
        if (spec == null) return null;
        String[] xz = spec.split(",");
        int x = Integer.parseInt(xz[0].strip());
        int z = Integer.parseInt(xz[1].strip());
        command(runner, "forceload add " + (x - 32) + " " + (z - 32) + " " + (x + 32) + " " + (z + 32));
        extra.put("far_area", x + "," + z);
        return new int[] {Math.floorDiv(x, 16), Math.floorDiv(z, 16)};
    }

    /** Per-chunk cost of the far area vs the busiest chunks, and the region layout, after the run. */
    private static void reportFarArea(HeadlessServerRunner runner, int[] far, Map<String, Object> extra) {
        extra.put(
                "chunkcost",
                command(runner, "multiforge chunkcost report minecraft:overworld " + far[0] + " " + far[1] + " 2")
                        .strip());
        extra.put("region_list", command(runner, "multiforge region list").strip());
        command(runner, "multiforge chunkcost off");
    }

    private static String command(HeadlessServerRunner runner, String cmd) {
        try {
            String reply = runner.rcon().command(cmd);
            System.out.println("SwarmBench: " + cmd + " -> " + reply.strip().replace('\n', ' '));
            return reply;
        } catch (IOException e) {
            System.err.println("SwarmBench: RCON error on '" + cmd + "': " + e.getMessage());
            return "";
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

    /**
     * {@code bench.mobs} persistent mobs on the surface of the armor-stand
     * footprint, so the forceloaded area carries the per-entity AI and events
     * a player's surroundings would (natural spawning needs a real player).
     */
    private static void spawnMobs(HeadlessServerRunner runner, int players, int mobs) throws IOException {
        if (mobs <= 0) return;
        String[] kinds = {"cow", "sheep", "pig", "chicken", "villager", "wolf", "spider", "iron_golem"};
        int side = (int) Math.ceil(Math.sqrt(Math.max(1, players)));
        int spreadRadius = Math.max(16, side * 8);
        Random rnd = new Random(11);
        for (int i = 0; i < mobs; i++) {
            int x = rnd.nextInt(2 * spreadRadius + 1) - spreadRadius;
            int z = rnd.nextInt(2 * spreadRadius + 1) - spreadRadius;
            runner.rcon()
                    .command(String.format(
                            Locale.ROOT,
                            "execute positioned %d 0 %d positioned over world_surface run summon minecraft:%s ~ ~ ~"
                                    + " {PersistenceRequired:1b}",
                            x,
                            z,
                            kinds[i % kinds.length]));
        }
        System.out.println("SwarmBench: spawned " + mobs + " mobs in the armor-stand footprint");
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
