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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Boots an installed server ({@link BenchSetup}) and relays console commands
 * from stdin over RCON, printing each reply: a scriptable way to poke a live
 * MultiForge or stock server during verification runs. {@code
 * bench.worldSource} starts it from a copy of an existing world. A line
 * {@code sleep <seconds>} pauses; end of input saves and stops the server.
 *
 * <pre>
 *   printf 'multiforge region list\nsleep 30\nmultiforge tickstats\n' | ./gradlew -q :multiforge-bench:console
 * </pre>
 */
public final class ServerConsole {
    public static void main(String[] args) throws Exception {
        int workers = Integer.getInteger("bench.workers", Runtime.getRuntime().availableProcessors());
        Path bootLog = Path.of(System.getProperty("bench.bootLog", "multiforge-bench/build/bench-logs/console.log"));
        HeadlessServerRunner.Config config = HeadlessServerRunner.Config.of(
                BenchSetup.install(), workers, System.getProperty("bench.seed", "1234567890"));
        String world = System.getProperty("bench.worldSource", "").trim();
        if (!world.isEmpty()) config = config.withWorld(Path.of(world));
        try (HeadlessServerRunner runner = new HeadlessServerRunner(config, bootLog)) {
            if (!runner.boot(new MetricsCollector())) {
                System.err.println("console: server failed to boot — see " + bootLog);
                System.exit(1);
            }
            System.out.println("console: " + BenchSetup.flavour() + " server up, game port " + runner.gamePort()
                    + ", log " + bootLog);
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("sleep ")) {
                    Thread.sleep((long) (Double.parseDouble(line.substring(6)) * 1000));
                    continue;
                }
                System.out.println("> " + line);
                System.out.println(runner.rcon().command(line).strip());
            }
            System.out.println("console: clean stop = " + runner.shutdown(Duration.ofSeconds(1)));
        }
    }

    private ServerConsole() {}
}
