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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point for {@code :multiforge-bench:stress} — MultiForge-only checks
 * with the {@code multiforge-testmods} stress fixtures. Nothing is compared
 * with stock NeoForge: each check has its own pass condition.
 *
 * <ul>
 *   <li>{@code limbo} — the v1.11 entity-limbo race, provoked for {@code
 *       bench.limboSeconds} (default 180) of real time. Two water basins,
 *       each straddling the edge of a forceloaded chunk, hold {@code
 *       bench.limboMobs} squid, glow squid and cod each; an {@code
 *       mftest_limbo} stand next to each basin forces and unforces the chunk
 *       across the edge every 7 ticks from its region's worker, and a third
 *       stand in a region of its own loads chunks from its worker every 2
 *       ticks, so the server thread pumps chunk promotions and demotions
 *       while the basins' workers move the mobs. Activation range is off, so
 *       every mob moves every tick. Every 30 s and at the end, {@code
 *       /multiforge entities audit} must show all three census gaps at 0 in
 *       every level, and {@code entity.limbo.healed} (the periodic auto-heal)
 *       must stay 0.</li>
 *   <li>{@code slowtick} — the watchdog: with {@code mftest_slowtick}, 120
 *       server ticks of 1 s each must not stop the server (Vanilla's watchdog
 *       reads the lag they accumulate as one 60 s tick), and then a single
 *       70 s tick must still be stopped by the watchdog.</li>
 * </ul>
 */
public final class StressRun {
    private static final Pattern GAPS = Pattern.compile(
            "(\\S+): known=(\\d+).*gaps known-visible=(-?\\d+) accessible-visible=(\\d+) ticking-ticklist=(\\d+)");
    private static final Pattern STAT = Pattern.compile("(\\w+)=(\\w+)");
    private static final Pattern ERROR_LINE =
            Pattern.compile("Exception in |ConcurrentModificationException|Exception ticking|Error executing task");

    public static void main(String[] args) throws Exception {
        String which = System.getProperty("bench.stress", "limbo,slowtick");
        Path logDir = Path.of(System.getProperty("bench.bootLog", "multiforge-bench/build/bench-logs/stress.log"))
                .getParent();
        boolean pass = true;
        for (String name : which.split(",")) {
            Map<String, Object> obs = new LinkedHashMap<>();
            boolean ok =
                    switch (name.strip()) {
                        case "limbo" -> limbo(logDir, obs);
                        case "slowtick" -> slowtick(logDir, obs);
                        default -> {
                            System.err.println("StressRun: unknown check " + name + " (known: limbo, slowtick)");
                            System.exit(2);
                            yield false;
                        }
                    };
            obs.forEach((k, v) -> System.out.println("  [" + name + "] " + k + " = " + v));
            System.out.println("StressRun: " + name + (ok ? " PASS" : " FAIL"));
            pass &= ok;
        }
        if (!pass) System.exit(1);
    }

    // ------------------------------------------------------------------
    // limbo
    // ------------------------------------------------------------------

    /**
     * Basin origins (block x, z of chunk A; chunk B is the next chunk east), away from
     * the spawn chunks, whose ticket would keep chunk B entity-ticking.
     */
    private static final int[][] BASINS = {{-2048, 0}, {0, -2048}};
    /** The loader stand's spot and the corner chunk of the area it loads. */
    private static final int[] LOADER = {2048, 2048};

    private static final int[] LOAD_AREA_CHUNK = {256, 256};

    static boolean limbo(Path logDir, Map<String, Object> obs) throws Exception {
        long seconds = Long.getLong("bench.limboSeconds", 180);
        int mobs = Integer.getInteger("bench.limboMobs", 150);
        int workers = Integer.getInteger("bench.workers", 4);
        Path log = logDir.resolve("stress-limbo.log");
        HeadlessServerRunner.Config config = HeadlessServerRunner.Config.of(
                        BenchSetup.install("multiforge"), workers, System.getProperty("bench.seed", "1234567890"))
                .withProperties(Map.of("difficulty", "easy"))
                .withExtraJvmArgs(
                        ("-Dmultiforge.entities.activation=false " + System.getProperty("bench.extraJvmArgs", ""))
                                .strip())
                .withMods(modsDir(logDir, "limbo"), null);
        obs.put("seconds", seconds);
        obs.put("mobs_per_basin", mobs);
        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, log)) {
            if (!runner.boot(new MetricsCollector())) {
                obs.put("error", "boot failed, see " + log);
                return false;
            }
            RconClient rcon = runner.rcon();
            rcon.command("gamerule doMobSpawning false");
            for (int[] b : BASINS) forceload(rcon, b[0] + 8, b[1] + 8);
            forceload(rcon, LOADER[0], LOADER[1]);
            for (int[] b : BASINS) {
                awaitLoaded(rcon, b[0] + 8, b[1] + 8);
                awaitBlockLoaded(rcon, b[0] + 24, b[1] + 8); // chunk B: block-ticking, from A's ticket
            }
            awaitLoaded(rcon, LOADER[0], LOADER[1]);
            for (int[] b : BASINS) {
                int x0 = b[0] + 4, x1 = b[0] + 27, z0 = b[1] + 1, z1 = b[1] + 14;
                rcon.command(
                        String.format(Locale.ROOT, "fill %d 100 %d %d 106 %d minecraft:glass hollow", x0, z0, x1, z1));
                rcon.command(String.format(
                        Locale.ROOT, "fill %d 101 %d %d 105 %d minecraft:water", x0 + 1, z0 + 1, x1 - 1, z1 - 1));
                String[] kinds = {"squid", "glow_squid", "cod"};
                for (int i = 0; i < mobs; i++) {
                    rcon.command(String.format(
                            Locale.ROOT,
                            "summon minecraft:%s %d.5 102 %d.5 {PersistenceRequired:1b}",
                            kinds[i % 3],
                            x0 + 1 + (i * 7) % (x1 - x0 - 1),
                            z0 + 1 + (i * 3) % (z1 - z0 - 1)));
                }
                rcon.command(String.format(
                        Locale.ROOT,
                        "summon minecraft:armor_stand %d 110 %d {Tags:[\"mftest_limbo\"],NoGravity:1b,Invulnerable:1b,"
                                + "NeoForgeData:{fx:%d,fz:%d}}",
                        b[0] + 2,
                        b[1] + 8,
                        Math.floorDiv(b[0], 16) + 1,
                        Math.floorDiv(b[1], 16)));
            }
            rcon.command(String.format(
                    Locale.ROOT,
                    "summon minecraft:armor_stand %d 110 %d {Tags:[\"mftest_limbo\"],NoGravity:1b,Invulnerable:1b,"
                            + "NeoForgeData:{lx:%d,lz:%d,every:2}}",
                    LOADER[0],
                    LOADER[1],
                    LOAD_AREA_CHUNK[0],
                    LOAD_AREA_CHUNK[1]));
            obs.put("mobs_total_start", countMobs(rcon));

            int[] maxGaps = new int[3];
            int audits = 0;
            List<String> dirty = new ArrayList<>();
            long deadline = System.currentTimeMillis() + seconds * 1000;
            while (System.currentTimeMillis() < deadline && runner.isAlive()) {
                Thread.sleep(Math.min(30_000, Math.max(1, deadline - System.currentTimeMillis())));
                if (!runner.isAlive()) break;
                audits++;
                audit(rcon, maxGaps, dirty);
            }
            if (!runner.isAlive()) {
                obs.put("error", "server died, see " + log);
                return false;
            }
            audits++;
            audit(rcon, maxGaps, dirty);
            ProbeSummary probes = ProbeSummary.parse(rcon.command("multiforge probes entity.") + "\n"
                    + rcon.command("multiforge probes region.main-thread-chunk-load"));
            long healed = probes.counters().getOrDefault("entity.limbo.healed", 0L);
            long pumps = probes.counters().getOrDefault("region.main-thread-chunk-load", 0L);
            Map<String, String> stats = stats(rcon.command("mftest_limbo stats"));
            obs.put("audits", audits);
            obs.put("max_gap_known_visible", maxGaps[0]);
            obs.put("max_gap_accessible_visible", maxGaps[1]);
            obs.put("max_gap_ticking_ticklist", maxGaps[2]);
            if (!dirty.isEmpty()) obs.put("dirty_audits", dirty.subList(0, Math.min(5, dirty.size())));
            obs.put("entity_limbo_healed", healed);
            obs.put("entity_visibility_deferred", probes.counters().getOrDefault("entity.visibility.deferred", 0L));
            obs.put("entity_visibility_unguarded", probes.counters().getOrDefault("entity.visibility.unguarded", 0L));
            obs.put(
                    "entity_visibility_replay_failed",
                    probes.counters().getOrDefault("entity.visibility.replay-failed", 0L));
            obs.put("worker_chunk_loads", pumps);
            obs.put("ticket_toggles", stats.get("toggles"));
            obs.put("fixture_loads", stats.get("loads"));
            obs.put("fixture_load_ms", stats.get("load_ms"));
            obs.put("mobs_total_end", countMobs(rcon));
            obs.put(
                    "census_types",
                    rcon.command("multiforge entities minecraft:overworld top 6")
                            .lines()
                            .filter(l -> l.contains("top types"))
                            .findFirst()
                            .orElse("?")
                            .strip());
            obs.put("mobs_by_type", countByType(rcon));
            obs.put(
                    "regions",
                    rcon.command("multiforge region list").lines().findFirst().orElse("?"));
            boolean clean = runner.shutdown(Duration.ofSeconds(2));
            int errors = errors(log);
            obs.put("errors", errors);
            obs.put("clean_stop", clean);
            return dirty.isEmpty()
                    && healed == 0
                    && pumps > 0
                    && Long.parseLong(stats.getOrDefault("toggles", "0")) > 0
                    && errors == 0;
        }
    }

    private static int countMobs(RconClient rcon) throws IOException {
        int n = 0;
        for (String t : List.of("squid", "glow_squid", "cod")) n += count(rcon, "@e[type=minecraft:" + t + "]");
        return n;
    }

    private static String countByType(RconClient rcon) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String t : List.of("squid", "glow_squid", "cod"))
            sb.append(t)
                    .append('=')
                    .append(count(rcon, "@e[type=minecraft:" + t + "]"))
                    .append(' ');
        return sb.toString().strip();
    }

    private static int count(RconClient rcon, String selector) throws IOException {
        Matcher m = Pattern.compile("count: (\\d+)").matcher(rcon.command("execute if entity " + selector));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static void audit(RconClient rcon, int[] maxGaps, List<String> dirty) throws IOException {
        String reply = rcon.command("multiforge entities audit");
        Matcher m = GAPS.matcher(reply);
        boolean any = false;
        while (m.find()) {
            any = true;
            int[] g = {Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))};
            for (int i = 0; i < 3; i++) maxGaps[i] = Math.max(maxGaps[i], Math.abs(g[i]));
            if (g[0] != 0 || g[1] != 0 || g[2] != 0) dirty.add(m.group(0));
        }
        if (!any) dirty.add("unparsed audit reply: " + reply.strip());
        System.out.println("StressRun: audit "
                + reply.lines().filter(l -> l.contains("gaps")).toList());
    }

    private static void forceload(RconClient rcon, int x, int z) throws IOException {
        rcon.command(String.format(Locale.ROOT, "forceload add %d %d", x, z));
    }

    private static void awaitLoaded(RconClient rcon, int x, int z) throws IOException, InterruptedException {
        String cmd = String.format(Locale.ROOT, "execute if loaded %d 0 %d", x, z);
        long deadline = System.currentTimeMillis() + 120_000;
        while (!rcon.command(cmd).contains("passed")) {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException(x + "," + z + " never loaded");
            Thread.sleep(250);
        }
    }

    /** {@code execute if loaded} wants an entity-ticking chunk; this only a loaded one. */
    private static void awaitBlockLoaded(RconClient rcon, int x, int z) throws IOException, InterruptedException {
        String cmd = String.format(Locale.ROOT, "execute if block %d -64 %d minecraft:bedrock", x, z);
        long deadline = System.currentTimeMillis() + 120_000;
        while (!rcon.command(cmd).contains("passed")) {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException(x + "," + z + " never loaded");
            Thread.sleep(250);
        }
    }

    // ------------------------------------------------------------------
    // slowtick
    // ------------------------------------------------------------------

    static boolean slowtick(Path logDir, Map<String, Object> obs) throws Exception {
        int workers = Integer.getInteger("bench.workers", 4);
        Path log = logDir.resolve("stress-slowtick.log");
        HeadlessServerRunner.Config config = HeadlessServerRunner.Config.of(
                        BenchSetup.install("multiforge"), workers, System.getProperty("bench.seed", "1234567890"))
                .withExtraJvmArgs(System.getProperty("bench.extraJvmArgs", ""))
                .withMods(modsDir(logDir, "slowtick"), null);
        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, log)) {
            if (!runner.boot(new MetricsCollector())) {
                obs.put("error", "boot failed, see " + log);
                return false;
            }
            RconClient rcon = runner.rcon();
            // 1. 120 ticks of 1 s: about two minutes of accumulated lag, no single long tick.
            rcon.command("mftest_slowtick run 120 1000");
            long deadline = System.currentTimeMillis() + 240_000;
            Map<String, String> stats = Map.of();
            while (System.currentTimeMillis() < deadline && runner.isAlive()) {
                Thread.sleep(5_000);
                try {
                    stats = stats(rcon.command("mftest_slowtick stats"));
                } catch (IOException e) {
                    if (!runner.isAlive()) break;
                    continue;
                }
                if ("0".equals(stats.get("remaining"))) break;
            }
            boolean survived = runner.isAlive() && "120".equals(stats.get("slow_ticks"));
            obs.put("slow_120x1s_ticks_done", stats.get("slow_ticks"));
            obs.put("slow_120x1s_survived", survived);
            obs.put("slow_120x1s_watchdog_fired", logContains(log, "A single server tick took"));
            if (!survived) return false;
            Thread.sleep(3_000); // let the catch-up settle
            obs.put("responsive_after", rcon.command("list").contains("players online"));

            // 2. One 70 s tick: the watchdog (max-tick-time 60 s) must stop the server.
            long armed = System.currentTimeMillis();
            rcon.command("mftest_slowtick run 1 70000");
            long killDeadline = armed + 150_000;
            while (runner.isAlive() && System.currentTimeMillis() < killDeadline) Thread.sleep(500);
            boolean killed = !runner.isAlive();
            obs.put("hang_70s_killed", killed);
            obs.put("hang_70s_killed_after_s", killed ? (System.currentTimeMillis() - armed) / 1000 : -1);
            obs.put("hang_70s_watchdog_logged", logContains(log, "A single server tick took"));
            return killed
                    && logContains(log, "A single server tick took")
                    && Boolean.TRUE.equals(obs.get("responsive_after"));
        }
    }

    // ------------------------------------------------------------------

    /** A mods directory with the {@code bench.testmods} jar whose name contains {@code mftest-<id>}. */
    private static Path modsDir(Path logDir, String id) throws IOException {
        String jars = System.getProperty("bench.testmods", "").trim();
        Path dir = logDir.resolve("stress-mods-" + id);
        HeadlessServerRunner.deleteRecursively(dir);
        Files.createDirectories(dir);
        for (String jar : jars.split(",")) {
            Path p = Path.of(jar.trim());
            if (p.getFileName().toString().startsWith("mftest-" + id)) Files.copy(p, dir.resolve(p.getFileName()));
        }
        try (var s = Files.list(dir)) {
            if (s.findAny().isEmpty())
                throw new IllegalStateException(
                        "no mftest-" + id + " jar in bench.testmods (run through " + ":multiforge-bench:stress)");
        }
        return dir;
    }

    private static Map<String, String> stats(String reply) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STAT.matcher(reply);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static boolean logContains(Path log, String needle) throws IOException {
        return Files.isRegularFile(log)
                && Files.readString(log, StandardCharsets.UTF_8).contains(needle);
    }

    private static int errors(Path log) throws IOException {
        if (!Files.isRegularFile(log)) return 0;
        int n = 0;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            if (ERROR_LINE.matcher(line).find()) {
                if (n < 5) System.err.println("  " + line);
                n++;
            }
        }
        return n;
    }

    private StressRun() {}
}
