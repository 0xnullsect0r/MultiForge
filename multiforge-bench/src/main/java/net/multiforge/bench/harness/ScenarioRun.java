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
 * Entry point for {@code :multiforge-bench:scenario} — the Phase X
 * behaviour checks, each run on stock NeoForge and on MultiForge (at {@code
 * bench.workers}, default 4) from the same seed, with the same commands and
 * protocol bots. A scenario records <em>observations</em>: outcomes that are
 * fixed by what was done, not by chance (an entity still exists, a block is
 * there, a count). The run passes when every observation matches between the
 * two servers and neither logged an error. MultiForge's ownership probes and
 * region count are reported alongside for the record.
 *
 * <ul>
 *   <li>{@code x1} — cross-region teleport: ten tagged mobs bounced 100
 *       times between four forceloaded spots 2000 blocks apart (separate
 *       regions under MultiForge) while the server ticks; each keeps its
 *       UUID, all ten end where the last teleport put them, and all ten are
 *       still there, same UUIDs, after a save and restart.</li>
 *   <li>{@code x2} — raid: a bot with Bad Omen stands in the nearest plains
 *       village; the omen turns into a Raid Omen, the raid starts and
 *       raiders spawn.</li>
 *   <li>{@code x3} — dragon fight: a bot enters the End, the dragon spawns,
 *       is killed by damage attributed to the bot, and its death runs the
 *       full sequence: exit portal lit, egg on the podium, one gateway.</li>
 *   <li>{@code x4} — the {@code multiforge-testmods} fixture mods: an entity
 *       in one region rewrites a block in another once a second (on
 *       MultiForge each write is rerouted and must still report {@code
 *       true}), and a {@code legacy}-declared mod counting entity ticks in
 *       plain collections must only ever be called on the server thread.</li>
 * </ul>
 */
public final class ScenarioRun {
    private static final Pattern COUNT = Pattern.compile("count: (\\d+)");
    private static final Pattern ERROR_LINE = Pattern.compile(
            "/ERROR\\]|Exception in |ConcurrentModificationException|Exception ticking|Error executing task");

    /** What a scenario sees of the server it runs against; {@link #restart} swaps in a new server. */
    static final class Ctx {
        final String flavour;
        final String name;
        final Path logDir;
        final List<Path> logs = new ArrayList<>();
        final Map<String, String> observations = new LinkedHashMap<>();
        HeadlessServerRunner.Config config;
        HeadlessServerRunner runner;
        MetricsCollector metrics;

        Ctx(String flavour, String name, HeadlessServerRunner.Config config, Path logDir) {
            this.flavour = flavour;
            this.name = name;
            this.config = config;
            this.logDir = logDir;
        }

        void boot(String suffix) throws Exception {
            Path log = logDir.resolve("scenario-" + name + "-" + flavour + suffix + ".log");
            logs.add(log);
            metrics = new MetricsCollector();
            runner = new HeadlessServerRunner(config, log);
            if (!runner.boot(metrics)) throw new IllegalStateException(flavour + " server failed to boot — see " + log);
        }

        /** Save and stop, then boot the same world again. */
        void restart() throws Exception {
            if (!runner.shutdown(Duration.ofSeconds(1))) throw new IllegalStateException("unclean stop");
            runner.close();
            Path saved = logDir.resolve("scenario-" + name + "-" + flavour + "-world");
            HeadlessServerRunner.deleteRecursively(saved);
            Files.move(config.install().dir().resolve("world"), saved);
            config = config.withWorld(saved);
            boot("-restart");
        }

        String cmd(String command) throws IOException {
            return runner.rcon().command(command).strip();
        }

        void observe(String key, Object value) {
            observations.put(key, String.valueOf(value));
            System.out.println("  [" + flavour + "] " + key + " = " + value);
        }

        /** Entities matching {@code selector} (in {@code dimension}, or the overworld when null). */
        int count(String dimension, String selector) throws IOException {
            String prefix = dimension == null ? "execute" : "execute in " + dimension;
            Matcher m = COUNT.matcher(cmd(prefix + " if entity " + selector));
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }

        boolean isBlock(String dimension, int x, int y, int z, String block) throws IOException {
            return cmd(String.format(Locale.ROOT, "execute in %s if block %d %d %d %s", dimension, x, y, z, block))
                    .contains("passed");
        }

        /** Run {@code ticks} ticks as fast as the server can and wait for them. */
        void sprint(long ticks) throws Exception {
            int before = metrics.sprintCompletions();
            cmd("tick sprint " + ticks);
            long deadline = System.currentTimeMillis() + Math.max(60_000, ticks * 50);
            while (metrics.sprintCompletions() == before && System.currentTimeMillis() < deadline && runner.isAlive()) {
                Thread.sleep(100);
            }
            if (metrics.sprintCompletions() == before)
                throw new IllegalStateException("sprint " + ticks + " did not finish");
        }

        /** Poll {@code check} every 250 ms of real time until true or {@code timeoutMs} passes. */
        boolean await(long timeoutMs, Check check) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (check.ok()) return true;
                Thread.sleep(250);
            }
            return check.ok();
        }
    }

    interface Check {
        boolean ok() throws Exception;
    }

    interface Scenario {
        void run(Ctx ctx) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        String which = System.getProperty("bench.scenario", "all");
        int workers = Integer.getInteger("bench.workers", 4);
        String seed = System.getProperty("bench.seed", "1234567890");
        Path logDir = Path.of(System.getProperty("bench.bootLog", "multiforge-bench/build/bench-logs/scenario.log"))
                .getParent();
        Map<String, Scenario> all = new LinkedHashMap<>();
        all.put("x1", ScenarioRun::crossRegionTeleport);
        all.put("x2", ScenarioRun::raid);
        all.put("x3", ScenarioRun::dragonFight);
        all.put("x4", ScenarioRun::fixtureMods);
        List<String> names = which.equals("all") ? List.copyOf(all.keySet()) : List.of(which.split(","));

        boolean pass = true;
        for (String name : names) {
            Scenario scenario = all.get(name);
            if (scenario == null) {
                System.err.println("ScenarioRun: unknown scenario " + name + " (known: " + all.keySet() + ")");
                System.exit(2);
            }
            System.out.println("=== " + name);
            Map<String, String> stock = runOn("stock", 1, seed, name, scenario, logDir);
            Map<String, String> mf = runOn("multiforge", workers, seed, name, scenario, logDir);
            List<String> diffs = new ArrayList<>();
            for (String key : stock.keySet()) {
                if (key.startsWith("info.")) continue;
                if (!stock.get(key).equals(mf.get(key))) {
                    diffs.add(key + ": stock=" + stock.get(key) + " multiforge=" + mf.get(key));
                }
            }
            for (String key : mf.keySet()) {
                if (!key.startsWith("info.") && !stock.containsKey(key)) diffs.add(key + ": only on multiforge");
            }
            if (diffs.isEmpty()) {
                System.out.println("ScenarioRun: " + name + " PASS");
            } else {
                pass = false;
                System.err.println("ScenarioRun: " + name + " FAIL");
                diffs.forEach(d -> System.err.println("  " + d));
            }
        }
        if (!pass) System.exit(1);
    }

    private static Map<String, String> runOn(
            String flavour, int workers, String seed, String name, Scenario scenario, Path logDir) throws Exception {
        HeadlessServerRunner.Config config = HeadlessServerRunner.Config.of(BenchSetup.install(flavour), workers, seed)
                .withProperties(Map.of("allow-flight", "true", "difficulty", "easy"));
        if (MOD_SCENARIOS.contains(name)) config = config.withMods(testModsDir(logDir), null);
        Ctx ctx = new Ctx(flavour, name, config, logDir);
        try {
            ctx.boot("");
            scenario.run(ctx);
        } catch (Exception e) {
            ctx.observe("scenario_error", e.toString());
        } finally {
            if (ctx.runner != null && ctx.runner.isAlive()) {
                if (ctx.runner.hasTickStats()) {
                    ProbeSummary probes =
                            ProbeSummary.collect(prefix -> ctx.cmd(("multiforge probes " + prefix).strip()));
                    ctx.observations.put("info.reroutes", String.valueOf(probes.reroutes()));
                    ctx.observations.put("info.reroute_mismatches", String.valueOf(probes.rerouteMismatches()));
                    ctx.observations.put("info.violations", String.valueOf(probes.violations()));
                    ctx.observations.put("info.overruns", String.valueOf(probes.overruns()));
                }
                ctx.runner.shutdown(Duration.ofSeconds(1));
            }
            if (ctx.runner != null) ctx.runner.close();
        }
        List<String> errors = new ArrayList<>();
        for (Path log : ctx.logs) {
            if (!Files.isRegularFile(log)) continue;
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (ERROR_LINE.matcher(line).find()) errors.add(line);
            }
        }
        ctx.observations.put("errors", String.valueOf(errors.size()));
        errors.stream().limit(5).forEach(e -> System.err.println("  [" + flavour + "] " + e));
        ctx.observations.entrySet().stream()
                .filter(e -> e.getKey().startsWith("info."))
                .forEach(e -> System.out.println("  [" + flavour + "] " + e.getKey() + " = " + e.getValue()));
        return ctx.observations;
    }

    /** Scenarios that run with the multiforge-testmods fixture jars in mods/. */
    private static final java.util.Set<String> MOD_SCENARIOS = java.util.Set.of("x4");

    /** A mods directory holding the jars listed in {@code bench.testmods} (comma-separated). */
    private static Path testModsDir(Path logDir) throws IOException {
        String jars = System.getProperty("bench.testmods", "").trim();
        if (jars.isEmpty())
            throw new IllegalStateException("bench.testmods is not set (run through :multiforge-bench:scenario)");
        Path dir = logDir.resolve("scenario-mods");
        HeadlessServerRunner.deleteRecursively(dir);
        Files.createDirectories(dir);
        for (String jar : jars.split(",")) {
            Path p = Path.of(jar.trim());
            Files.copy(p, dir.resolve(p.getFileName()));
        }
        return dir;
    }

    // ------------------------------------------------------------------
    // X.1 — cross-region teleport
    // ------------------------------------------------------------------

    private static final int[][] SPOTS = {{8, 8}, {2008, 8}, {8, 2008}, {2008, 2008}};

    static void crossRegionTeleport(Ctx ctx) throws Exception {
        for (int[] s : SPOTS) ctx.cmd(String.format(Locale.ROOT, "forceload add %d %d", s[0], s[1]));
        if (!ctx.await(120_000, () -> {
            for (int[] s : SPOTS) {
                if (!ctx.cmd(String.format(Locale.ROOT, "execute if loaded %d 0 %d", s[0], s[1]))
                        .contains("passed")) {
                    return false;
                }
            }
            return true;
        })) throw new IllegalStateException("teleport spots never loaded");
        for (int i = 0; i < 10; i++) {
            ctx.cmd(String.format(
                    Locale.ROOT,
                    "summon minecraft:pig 8 200 8 {Tags:[\"mfx1\",\"mfx1_%d\"],NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}",
                    i));
        }
        List<String> before = uuids(ctx);
        ctx.observe("summoned", ctx.count(null, "@e[tag=mfx1]"));
        int minSeen = 10;
        for (int i = 0; i < 100; i++) {
            int[] s = SPOTS[i % 4];
            ctx.cmd(String.format(Locale.ROOT, "execute as @e[tag=mfx1] run tp @s %d 200 %d", s[0], s[1]));
            Thread.sleep(60); // let the server tick between hops
            if (i % 10 == 9) minSeen = Math.min(minSeen, ctx.count(null, "@e[tag=mfx1]"));
        }
        ctx.sprint(40);
        ctx.observe("min_count_during_hops", minSeen);
        int[] last = SPOTS[99 % 4];
        ctx.observe(
                "at_final_spot",
                ctx.count(
                        null,
                        String.format(Locale.ROOT, "@e[tag=mfx1,x=%d,y=200,z=%d,distance=..1]", last[0], last[1])));
        ctx.observe("uuids_kept", before.equals(uuids(ctx)));
        if (ctx.runner.hasTickStats()) ctx.observations.put("info.regions", regions(ctx));
        ctx.restart();
        // Entities load from their own storage a little after their chunks.
        ctx.await(60_000, () -> ctx.count(null, "@e[tag=mfx1]") >= 10);
        ctx.observe("after_restart", ctx.count(null, "@e[tag=mfx1]"));
        ctx.observe("uuids_kept_after_restart", before.equals(uuids(ctx)));
    }

    private static List<String> uuids(Ctx ctx) throws IOException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String reply = ctx.cmd("data get entity @e[tag=mfx1_" + i + ",limit=1] UUID");
            int bracket = reply.indexOf('[');
            out.add(bracket < 0 ? "missing" : reply.substring(bracket));
        }
        return out;
    }

    private static String regions(Ctx ctx) throws IOException {
        return ctx.cmd("multiforge region list")
                .lines()
                .filter(l -> l.contains("region(s)"))
                .findFirst()
                .orElse("?");
    }

    // ------------------------------------------------------------------
    // X.2 — raid
    // ------------------------------------------------------------------

    private static final Pattern LOCATED = Pattern.compile("\\[(-?\\d+), [^,]+, (-?\\d+)\\]");

    static void raid(Ctx ctx) throws Exception {
        Matcher m = LOCATED.matcher(ctx.cmd("locate structure minecraft:village_plains"));
        if (!m.find()) throw new IllegalStateException("no plains village found");
        int vx = Integer.parseInt(m.group(1));
        int vz = Integer.parseInt(m.group(2));
        ctx.cmd(String.format(Locale.ROOT, "forceload add %d %d %d %d", vx - 48, vz - 48, vx + 48, vz + 48));
        try (BotSwarm bots = new BotSwarm("127.0.0.1", ctx.runner.gamePort(), 6)) {
            bots.setWalking(false);
            bots.connect("mfraid", 1, 1);
            if (bots.awaitJoined(1, 60_000) < 1) throw new IllegalStateException("bot did not join");
            ctx.cmd("gamemode creative mfraid0");
            ctx.cmd(String.format(
                    Locale.ROOT,
                    "execute positioned %d 0 %d positioned over motion_blocking run tp mfraid0 ~ ~ ~",
                    vx,
                    vz));
            ctx.cmd("effect give mfraid0 minecraft:bad_omen 1200 0");
            // Bad Omen -> Raid Omen in a village, Raid Omen expires after 30 s -> raid,
            // first wave after the raid's 300-tick countdown.
            boolean started = ctx.await(90_000, () -> ctx.count(null, "@e[type=#minecraft:raiders]") > 0);
            ctx.observe(
                    "raid_omen_applied",
                    ctx.cmd("execute if entity @a[name=mfraid0,nbt={active_effects:[{id:\"minecraft:raid_omen\"}]}]")
                                    .contains("passed")
                            || started);
            ctx.observe("raiders_spawned", started);
        }
    }

    // ------------------------------------------------------------------
    // X.3 — dragon fight
    // ------------------------------------------------------------------

    static void dragonFight(Ctx ctx) throws Exception {
        String end = "minecraft:the_end";
        try (BotSwarm bots = new BotSwarm("127.0.0.1", ctx.runner.gamePort(), 8)) {
            bots.setWalking(false);
            bots.connect("mfend", 1, 1);
            if (bots.awaitJoined(1, 60_000) < 1) throw new IllegalStateException("bot did not join");
            ctx.cmd("gamemode creative mfend0");
            ctx.cmd("execute in minecraft:the_end run tp mfend0 0 90 0");
            boolean spawned = ctx.await(120_000, () -> ctx.count(end, "@e[type=minecraft:ender_dragon]") > 0);
            ctx.observe("dragon_spawned", spawned);
            if (!spawned) return;
            // Let the fight finish initialising (portal scan, boss bar, dragon link)
            // before the killing blow; a real fight never ends on its first tick.
            ctx.sprint(100);
            ctx.cmd(
                    "execute in minecraft:the_end run damage @e[type=minecraft:ender_dragon,limit=1] 1000 minecraft:player_attack by mfend0");
            // The dying dragon flies back to the podium, then plays its 200-tick death
            // animation (no longer matched by @e: it is not alive), then the exit
            // portal lights, the egg appears and the first gateway opens.
            int[] eggAt = {-1};
            for (int t = 0; t < 1600 && eggAt[0] < 0; t += 50) {
                ctx.sprint(50);
                eggAt[0] = findEgg(ctx, end);
            }
            ctx.observe("dragon_alive", ctx.count(end, "@e[type=minecraft:ender_dragon]") > 0);
            int eggY = eggAt[0];
            ctx.observe("egg_on_podium", eggY >= 0);
            boolean portal = false;
            for (int y = eggY - 6; y < eggY && eggY >= 0; y++) {
                if (ctx.isBlock(end, 1, y, 0, "minecraft:end_portal")) portal = true;
            }
            ctx.observe("exit_portal_lit", portal);
            int gateways = 0;
            for (int i = 0; i < 20; i++) {
                double angle = 2.0 * (-Math.PI + 0.15707963267948966 * i);
                int gx = (int) Math.floor(96.0 * Math.cos(angle));
                int gz = (int) Math.floor(96.0 * Math.sin(angle));
                if (ctx.isBlock(end, gx, 75, gz, "minecraft:end_gateway")) gateways++;
            }
            ctx.observe("gateways", gateways);
        }
    }

    // ------------------------------------------------------------------
    // X.4 — fixture mods: a cross-region writer and a legacy listener
    // ------------------------------------------------------------------

    private static final Pattern STAT = Pattern.compile("(\\w+)=(\\w+)");

    static void fixtureMods(Ctx ctx) throws Exception {
        int[][] spots = {{8, 8}, {2008, 8}};
        for (int[] s : spots) ctx.cmd(String.format(Locale.ROOT, "forceload add %d %d", s[0], s[1]));
        if (!ctx.await(
                120_000,
                () -> ctx.cmd("execute if loaded 8 0 8").contains("passed")
                        && ctx.cmd("execute if loaded 2008 0 8").contains("passed"))) {
            throw new IllegalStateException("fixture spots never loaded");
        }
        // The writer stands in one region and targets a block in the other.
        ctx.cmd("summon minecraft:armor_stand 8 100 8 {Tags:[\"mftest_writer\"],NoGravity:1b,"
                + "NeoForgeData:{tx:2008,ty:100,tz:8}}");
        // Entity ticks in both regions for the legacy listener to count.
        for (int[] s : spots) {
            for (int i = 0; i < 10; i++) {
                ctx.cmd(String.format(
                        Locale.ROOT,
                        "summon minecraft:pig %d 120 %d {NoAI:1b,NoGravity:1b,PersistenceRequired:1b}",
                        s[0] + i,
                        s[1]));
            }
        }
        ctx.sprint(400);
        if (ctx.runner.hasTickStats()) ctx.observations.put("info.regions", regions(ctx));
        ctx.observe(
                "target_is_wool",
                ctx.cmd("execute if block 2008 100 8 #minecraft:wool").contains("passed"));
        Map<String, String> writer = stats(ctx.cmd("mftest_writer stats"));
        ctx.observations.put("info.writes", writer.get("writes"));
        ctx.observe("writes_at_least_15", Long.parseLong(writer.getOrDefault("writes", "0")) >= 15);
        ctx.observe("writer_false_returns", writer.get("false_returns"));
        Map<String, String> legacy = stats(ctx.cmd("mftest_legacy stats"));
        ctx.observations.put("info.legacy_ticks", legacy.get("ticks"));
        ctx.observe("legacy_counted_ticks", Long.parseLong(legacy.getOrDefault("ticks", "0")) > 0);
        ctx.observe("legacy_off_server_thread", legacy.get("off_server_thread"));
        ctx.observe("legacy_consistent", legacy.get("consistent"));
    }

    private static Map<String, String> stats(String reply) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STAT.matcher(reply);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    /** Y of the dragon egg above the exit portal at (0, 0), or -1. */
    private static int findEgg(Ctx ctx, String end) throws IOException {
        for (int y = 40; y < 120; y++) {
            if (ctx.isBlock(end, 0, y, 0, "minecraft:dragon_egg")) return y;
        }
        return -1;
    }

    private ScenarioRun() {}
}
