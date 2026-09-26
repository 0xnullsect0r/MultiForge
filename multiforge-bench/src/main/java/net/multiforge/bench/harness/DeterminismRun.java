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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import net.multiforge.bench.determinism.TerrainHash;

/**
 * Entry point for {@code :multiforge-bench:determinism} — the vanilla-parity
 * gate from CLAUDE.md: tick one world on stock NeoForge and on MultiForge and
 * check that the terrain comes out the same, chunk for chunk.
 *
 * <p><b>Why a shared seed world.</b> Stock NeoForge does not reproduce its own
 * world generation: small decorations at chunk borders (grass, ferns) depend
 * on which neighbour decorated first, and generation runs in parallel. So the
 * gate generates the world once, frozen (no ticks run), on stock NeoForge:
 * reproducible gamerules (no random ticks, spawning, weather, fire or
 * daylight, and no entities — world generation spawns animals whose AI runs on
 * the unseeded level random), four {@code (2r+1)²}-chunk forceloaded squares centred at
 * {@code (±s, ±s)} chunks — far enough apart ({@code bench.spacing}, default
 * 48) that MultiForge ticks them as separate regions — the spawn chunks near
 * the origin sit between them, and sections that touch, even diagonally, merge — saved as soon as every
 * chunk in them is loaded. That world is the fixed input,
 * cached under {@code <cacheDir>/determinism/}.
 *
 * <p>Each target — stock NeoForge (the reference), then MultiForge at every
 * worker count in {@code bench.workers} (default {@code 1}) — gets a fresh
 * copy, boots (the forceload tickets are saved in the world), sprints {@code
 * bench.ticks} ticks so the block and fluid ticks world generation queued run
 * — under MultiForge, inside regions — then saves and stops. Each result's
 * full chunks inside the squares are hashed ({@link TerrainHash}) and compared
 * with the stock result. Any difference fails the gate and lists the chunks.
 */
public final class DeterminismRun {
    private static final List<String> GAMERULES = List.of(
            "randomTickSpeed 0",
            "doMobSpawning false",
            "doPatrolSpawning false",
            "doTraderSpawning false",
            "doWardenSpawning false",
            "doInsomnia false",
            "doWeatherCycle false",
            "doFireTick false",
            "doDaylightCycle false");

    public static void main(String[] args) throws Exception {
        String seed = System.getProperty("bench.seed", "1234567890");
        int radius = Integer.getInteger("bench.radius", 3);
        int spacing = Integer.getInteger("bench.spacing", 48);
        List<int[]> centres = List.of(
                new int[] {-spacing, -spacing}, new int[] {spacing, -spacing},
                new int[] {-spacing, spacing}, new int[] {spacing, spacing});
        long ticks = Long.getLong("bench.ticks", 1200L);
        List<Integer> workerCounts = new ArrayList<>();
        for (String w : System.getProperty("bench.workers", "1").split(","))
            workerCounts.add(Integer.parseInt(w.trim()));
        Path cache = Path.of(System.getProperty("bench.cacheDir", "multiforge-bench/build"))
                .resolve("determinism");
        Path logDir = Path.of(System.getProperty("bench.bootLog", "multiforge-bench/build/bench-logs/determinism.log"))
                .getParent();
        if (4 * (2 * radius + 1) * (2 * radius + 1) > 256 || spacing <= 2 * radius) {
            System.err.println("DeterminismRun: four squares of radius " + radius
                    + " exceed /forceload's 256-chunk limit (max radius 3), or overlap (spacing " + spacing + ")");
            System.exit(2);
        }
        Area area = new Area(centres, radius);
        System.out.println(
                "DeterminismRun: seed=" + seed + " radius=" + radius + " ticks=" + ticks + " workers=" + workerCounts);

        Path seedWorld = cache.resolve("seed-" + seed + "-r" + radius + "-s" + spacing);
        if (!Files.isDirectory(seedWorld) || Boolean.getBoolean("bench.regenerate")) {
            generate(seed, area, seedWorld, logDir.resolve("determinism-generate.log"));
        }

        TreeMap<String, String> reference = tick("stock", 1, seed, area, ticks, seedWorld, cache, logDir);
        int expected = area.chunkCount();
        if (reference.size() != expected) {
            System.err.println("DeterminismRun: stock left " + reference.size()
                    + " full chunks in the square, expected " + expected);
            System.exit(1);
        }
        boolean pass = true;
        for (int workers : workerCounts) {
            TreeMap<String, String> result = tick("multiforge", workers, seed, area, ticks, seedWorld, cache, logDir);
            List<String> diffs = TerrainHash.differences(reference, result);
            if (diffs.isEmpty()) {
                System.out.println("DeterminismRun: PASS — MultiForge at " + workers + " worker(s) matches stock ("
                        + result.size() + " chunks, digest " + TerrainHash.digest(result) + ")");
            } else {
                pass = false;
                System.err.println("DeterminismRun: FAIL — MultiForge at " + workers + " worker(s): " + diffs.size()
                        + " chunk(s) differ from stock; worlds kept under " + cache);
                diffs.stream().limit(50).forEach(d -> System.err.println("  " + d));
            }
        }
        if (!pass) System.exit(1);
    }

    /** Generate the frozen seed world on stock NeoForge and move it to {@code target}. */
    private static void generate(String seed, Area area, Path target, Path log) throws Exception {
        System.out.println("DeterminismRun: generating the seed world on stock NeoForge");
        ServerInstall install = BenchSetup.install("stock");
        MetricsCollector metrics = new MetricsCollector();
        try (HeadlessServerRunner runner =
                new HeadlessServerRunner(HeadlessServerRunner.Config.of(install, 1, seed), log)) {
            if (!runner.boot(metrics)) fail("seed-world server failed to boot — see " + log);
            var rcon = runner.rcon();
            rcon.command("tick freeze");
            for (String rule : GAMERULES) rcon.command("gamerule " + rule);
            rcon.command("weather clear");
            for (int[] c : area.centres()) {
                int r = area.radius();
                String forced = rcon.command(String.format(
                        Locale.ROOT,
                        "forceload add %d %d %d %d",
                        (c[0] - r) * 16,
                        (c[1] - r) * 16,
                        (c[0] + r) * 16 + 15,
                        (c[1] + r) * 16 + 15));
                System.out.println("DeterminismRun: " + forced.strip());
            }
            waitLoaded(runner, area);
            // World generation spawns animals, and their AI (a sheep eating grass)
            // runs on the unseeded level random: no entities in the fixed input.
            System.out.println("DeterminismRun: "
                    + rcon.command("kill @e[type=!minecraft:player]").strip());
            if (!runner.shutdown(Duration.ofSeconds(1))) fail("seed-world server did not stop cleanly — see " + log);
        }
        HeadlessServerRunner.deleteRecursively(target);
        Files.createDirectories(target.getParent());
        Files.move(install.dir().resolve("world"), target);
    }

    /** Tick a copy of the seed world on {@code flavour} and return its terrain inside the square. */
    private static TreeMap<String, String> tick(
            String flavour, int workers, String seed, Area area, long ticks, Path seedWorld, Path cache, Path logDir)
            throws Exception {
        String name = flavour + "-w" + workers;
        Path log = logDir.resolve("determinism-" + name + ".log");
        ServerInstall install = BenchSetup.install(flavour);
        MetricsCollector metrics = new MetricsCollector();
        HeadlessServerRunner.Config config =
                HeadlessServerRunner.Config.of(install, workers, seed).withWorld(seedWorld);
        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, log)) {
            if (!runner.boot(metrics)) fail(name + " failed to boot — see " + log);
            runner.rcon().command("tick freeze");
            waitLoaded(runner, area);
            if (runner.hasTickStats()) {
                String regions = runner.rcon().command("multiforge region list");
                System.out.println("DeterminismRun: " + name + " regions:\n" + regions.strip());
            }
            runner.rcon().command("tick sprint " + ticks);
            long deadline = System.currentTimeMillis() + Math.max(120_000, ticks * 100);
            while (!metrics.sawSprintCompleted() && System.currentTimeMillis() < deadline && runner.isAlive()) {
                Thread.sleep(200);
            }
            if (!metrics.sawSprintCompleted()) fail(name + ": sprint did not complete — see " + log);
            if (!runner.shutdown(Duration.ofSeconds(1))) fail(name + " did not stop cleanly — see " + log);
        }
        Path kept = cache.resolve(name);
        HeadlessServerRunner.deleteRecursively(kept);
        Files.move(install.dir().resolve("world"), kept);
        TreeMap<String, String> chunks = TerrainHash.compute(kept);
        chunks.keySet().removeIf(k -> !area.contains(k));
        System.out.println("DeterminismRun: " + name + " — " + chunks.size() + " full chunks, digest "
                + TerrainHash.digest(chunks));
        return chunks;
    }

    /** Poll {@code execute if loaded} for every chunk of the squares until all are loaded (10 min cap). */
    private static void waitLoaded(HeadlessServerRunner runner, Area area) throws Exception {
        long deadline = System.currentTimeMillis() + 600_000;
        for (int[] chunk : area.chunks()) {
            String cmd = String.format(Locale.ROOT, "execute if loaded %d 0 %d", chunk[0] * 16 + 8, chunk[1] * 16 + 8);
            while (!runner.rcon().command(cmd).contains("passed")) {
                if (System.currentTimeMillis() > deadline || !runner.isAlive()) {
                    fail("chunk " + chunk[0] + "," + chunk[1] + " never loaded");
                }
                Thread.sleep(250);
            }
        }
    }

    /** The forceloaded squares: {@code radius} chunks around each centre, overworld only. */
    record Area(List<int[]> centres, int radius) {
        int chunkCount() {
            return centres.size() * (2 * radius + 1) * (2 * radius + 1);
        }

        List<int[]> chunks() {
            List<int[]> out = new ArrayList<>();
            for (int[] c : centres) {
                for (int x = c[0] - radius; x <= c[0] + radius; x++) {
                    for (int z = c[1] - radius; z <= c[1] + radius; z++) out.add(new int[] {x, z});
                }
            }
            return out;
        }

        /** Whether a {@link TerrainHash} chunk key lies in one of the squares. */
        boolean contains(String key) {
            String[] parts = key.split(" ");
            if (!parts[0].equals("minecraft:overworld")) return false;
            int x = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            for (int[] c : centres) {
                if (Math.abs(x - c[0]) <= radius && Math.abs(z - c[1]) <= radius) return true;
            }
            return false;
        }
    }

    private static void fail(String message) {
        System.err.println("DeterminismRun: " + message);
        System.exit(1);
    }

    private DeterminismRun() {}
}
