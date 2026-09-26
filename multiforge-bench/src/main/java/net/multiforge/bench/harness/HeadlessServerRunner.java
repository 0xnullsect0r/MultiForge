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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Boots one headless server from an installed server directory ({@link
 * ServerInstall}), drives it over RCON, and tears it down.
 *
 * <p>The JVM is this harness's own child process — launched straight from
 * the installer's {@code unix_args.txt} with {@code $JAVA_HOME/bin/java}
 * (or {@code java} on {@code PATH}) — so its pid, exit status and RSS are
 * observed directly. The game and RCON ports are picked free per run, so
 * two runners (a MultiForge server and a stock baseline) can coexist.
 *
 * <p>Talks to the server only over TCP (RCON, and the game port for {@link
 * BotSwarm}) and by tailing its console log — no {@code net.minecraft.*}
 * import, so {@code multiforge-bench} stays MC-free.
 *
 * <p>Timing comes from {@code /multiforge tickstats} when the server is
 * MultiForge: every tick since {@link #beginMeasurement()}, with the true
 * maximum and a TPS count over the last ten minutes of wall time. A stock
 * NeoForge baseline has no such command, so there it falls back to
 * vanilla's {@code /tick query} (last 100 ticks, no maximum) and the result
 * says so ({@code timing_source}).
 */
public final class HeadlessServerRunner implements AutoCloseable {

    private static final Duration RCON_BOOT_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration RCON_IO_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration JVM_EXIT_TIMEOUT =
            Duration.ofMinutes(20); // saving a world 100 bots explored takes minutes
    private static final String RCON_PASSWORD = "multiforge";
    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+)");

    /**
     * @param install the installed server directory to run in
     * @param workers value passed as {@code -Dmultiforge.workers=} (ignored by a stock server)
     * @param levelSeed fixed seed for reproducible captures
     * @param maxPlayers {@code server.properties} {@code max-players}
     * @param modsSourceDir if non-null, its jars become the run's {@code mods/}; otherwise {@code mods/} is emptied
     * @param configSourceDir if non-null, copied over the run dir's {@code config/}
     * @param extraJvmArgs extra whitespace-separated {@code -D}/{@code -X} flags for the server JVM
     * @param serverProperties extra {@code server.properties} entries, overriding the defaults
     * @param worldSource if non-null, copied in as the run's {@code world/}; otherwise the run starts a new world
     */
    public record Config(
            ServerInstall install,
            int workers,
            String levelSeed,
            int maxPlayers,
            Path modsSourceDir,
            Path configSourceDir,
            String extraJvmArgs,
            Map<String, String> serverProperties,
            Path worldSource) {

        public Config {
            serverProperties = serverProperties == null ? Map.of() : Map.copyOf(serverProperties);
            extraJvmArgs = extraJvmArgs == null ? "" : extraJvmArgs;
        }

        public static Config of(ServerInstall install, int workers, String levelSeed) {
            return new Config(install, workers, levelSeed, 20, null, null, "", Map.of(), null);
        }

        public Config withMaxPlayers(int n) {
            return new Config(
                    install,
                    workers,
                    levelSeed,
                    n,
                    modsSourceDir,
                    configSourceDir,
                    extraJvmArgs,
                    serverProperties,
                    worldSource);
        }

        public Config withMods(Path mods, Path config) {
            return new Config(
                    install, workers, levelSeed, maxPlayers, mods, config, extraJvmArgs, serverProperties, worldSource);
        }

        public Config withExtraJvmArgs(String args) {
            return new Config(
                    install,
                    workers,
                    levelSeed,
                    maxPlayers,
                    modsSourceDir,
                    configSourceDir,
                    args,
                    serverProperties,
                    worldSource);
        }

        public Config withWorld(Path world) {
            return new Config(
                    install,
                    workers,
                    levelSeed,
                    maxPlayers,
                    modsSourceDir,
                    configSourceDir,
                    extraJvmArgs,
                    serverProperties,
                    world);
        }

        public Config withProperties(Map<String, String> props) {
            Map<String, String> merged = new LinkedHashMap<>(serverProperties);
            merged.putAll(props);
            return new Config(
                    install,
                    workers,
                    levelSeed,
                    maxPlayers,
                    modsSourceDir,
                    configSourceDir,
                    extraJvmArgs,
                    merged,
                    worldSource);
        }
    }

    private final Config config;
    private final Path runDir;
    private final Path bootLog;
    private final int gamePort;
    private final int rconPort;

    private Process serverProcess;
    private RconClient rcon;
    private MetricsCollector metricsSink;
    private boolean hasTickStats;
    private long windowStartGameTime = -1;
    private long windowStartNanos;

    private final AtomicLong rssPeakKb = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public HeadlessServerRunner(Config config, Path bootLogPath) throws IOException {
        this.config = config;
        this.runDir = config.install().dir();
        this.bootLog = bootLogPath;
        this.gamePort = freePort();
        this.rconPort = freePort();
    }

    /**
     * Boots the server. Returns {@code false} (rather than throwing) if RCON
     * never comes up within {@link #RCON_BOOT_TIMEOUT} or the JVM exits first —
     * a boot failure is a legitimate bench outcome ({@code boot_ok: false}).
     */
    public boolean boot(MetricsCollector metricsSink) throws IOException, InterruptedException {
        this.metricsSink = metricsSink;
        if (bootLog.getParent() != null) {
            Files.createDirectories(bootLog.getParent());
        }
        prepareRunDir();

        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Dmultiforge.workers=" + config.workers());
        if (!config.extraJvmArgs().isBlank()) {
            jvmArgs.addAll(List.of(config.extraJvmArgs().trim().split("\\s+")));
        }
        ProcessBuilder pb = new ProcessBuilder(config.install().command(jvmArgs));
        pb.directory(runDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(bootLog.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);

        running.set(true);
        serverProcess = pb.start();
        startRssSampler();
        startLogTail();

        if (!waitForBootLogPattern("RCON running on", RCON_BOOT_TIMEOUT)) {
            running.set(false);
            return false;
        }

        rcon = new RconClient("127.0.0.1", rconPort, RCON_PASSWORD, RCON_IO_TIMEOUT);
        // The RCON bind line lands a beat before the socket reliably accepts
        // and authenticates; probe with a real command until it answers.
        long probeDeadline = System.currentTimeMillis() + 15_000;
        IOException lastError = null;
        while (System.currentTimeMillis() < probeDeadline) {
            try {
                rcon.command("list");
                String reply = rcon.command("multiforge tickstats reset");
                // A stock server echoes the unknown command back, "reset" included.
                hasTickStats = reply.contains("Tick statistics reset");
                return true;
            } catch (IOException e) {
                lastError = e;
                Thread.sleep(500);
            }
        }
        running.set(false);
        throw new IOException("RCON never became responsive after boot", lastError);
    }

    public RconClient rcon() {
        return rcon;
    }

    public int gamePort() {
        return gamePort;
    }

    public long serverPid() {
        return serverProcess == null ? -1 : serverProcess.pid();
    }

    /** Peak resident set size of the server JVM, sampled once a second. */
    public long rssPeakMb() {
        return rssPeakKb.get() / 1024;
    }

    /** Whether the server has {@code /multiforge tickstats} (false for a stock NeoForge baseline). */
    public boolean hasTickStats() {
        return hasTickStats;
    }

    public boolean isAlive() {
        return serverProcess != null && serverProcess.isAlive();
    }

    /** Start the measured window: resets {@code /multiforge tickstats} so boot and setup ticks don't count. */
    public void beginMeasurement() throws IOException {
        if (hasTickStats) rcon.command("multiforge tickstats reset");
        windowStartGameTime = gameTime();
        windowStartNanos = System.nanoTime();
    }

    /** Close the measured window: folds the tick statistics (or a {@code /tick query} fallback) into the sink. */
    public void endMeasurement() {
        try {
            long ticks = gameTime() - windowStartGameTime;
            if (windowStartGameTime >= 0 && ticks >= 0) {
                metricsSink.recordGameTimeWindow(ticks, (System.nanoTime() - windowStartNanos) / 1e9);
            }
        } catch (IOException e) {
            System.err.println("bench: game time query failed: " + e.getMessage());
        }
        try {
            if (hasTickStats) metricsSink.recordTickStats(rcon.command("multiforge tickstats"));
        } catch (IOException e) {
            System.err.println("bench: tickstats query failed: " + e.getMessage());
        }
        safeQuery();
    }

    /**
     * Freezes the tick loop, sprints exactly {@code ticks} game-ticks via
     * {@code /tick sprint}, and waits for the "Sprint completed" log line.
     *
     * <p>A sprint runs ticks back to back with no 50 ms pacing, so it measures
     * per-tick compute cost quickly; the per-tick durations {@code tickstats}
     * records are the same whether or not the server then sleeps. It does not
     * measure whether real-time load holds 20 TPS — the swarm profile does.
     */
    public void runSprintProfile(long ticks) throws IOException, InterruptedException {
        rcon.command("tick freeze");
        beginMeasurement();
        rcon.command("tick sprint " + ticks);

        long timeoutMs = Math.max(60_000, ticks * 20);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !metricsSink.sawSprintCompleted() && isAlive()) {
            Thread.sleep(200);
        }
        endMeasurement();
        rcon.command("tick unfreeze");
    }

    /** The overworld's game time ({@code /time query gametime}), or -1 when the reply has no number. */
    public long gameTime() throws IOException {
        Matcher m = LAST_NUMBER.matcher(rcon.command("time query gametime"));
        long value = -1;
        while (m.find()) value = Long.parseLong(m.group(1));
        return value;
    }

    /** Issues one {@code /tick query} and feeds the reply to the metrics sink; failures are swallowed. */
    public void safeQuery() {
        try {
            metricsSink.recordTickQueryReply(rcon.command("tick query"));
        } catch (IOException e) {
            // RCON can be briefly slow under load; one missed sample is not a failed run.
        }
    }

    /**
     * Flushes and stops the server, then waits for the JVM to exit,
     * force-killing it after {@link #JVM_EXIT_TIMEOUT}.
     *
     * @return {@code true} if {@code save-all flush} and {@code stop} both
     *     round-tripped over RCON and the JVM exited on its own with status 0.
     */
    public boolean shutdown(Duration settleBeforeStop) throws InterruptedException {
        boolean cleanStop = true;
        if (settleBeforeStop != null) {
            Thread.sleep(settleBeforeStop.toMillis());
        }
        try {
            // `stop` saves every level itself. A separate `save-all flush` of a big
            // world can outlast the RCON read timeout, and then `stop` was never sent.
            rcon.command("stop");
        } catch (IOException e) {
            // The server may close RCON before it answers; the exit code decides.
        }
        if (!serverProcess.waitFor(JVM_EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            cleanStop = false;
            dumpThreads("stop-timeout");
            serverProcess.destroy();
            if (!serverProcess.waitFor(10, TimeUnit.SECONDS)) serverProcess.destroyForcibly();
            serverProcess.waitFor(10, TimeUnit.SECONDS);
        } else if (serverProcess.exitValue() != 0) {
            cleanStop = false;
        }
        running.set(false);
        return cleanStop;
    }

    /**
     * Write the server JVM's thread dump next to the boot log ({@code
     * <bootLog>.<label>.threads.txt}), so a server that would not stop leaves
     * evidence of where it was. Best effort.
     */
    private void dumpThreads(String label) {
        try {
            Path jstack = Path.of(System.getProperty("java.home"), "bin", "jstack");
            Path out = bootLog.resolveSibling(bootLog.getFileName() + "." + label + ".threads.txt");
            Process p = new ProcessBuilder(jstack.toString(), Long.toString(serverProcess.pid()))
                    .redirectErrorStream(true)
                    .redirectOutput(out.toFile())
                    .start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly();
            System.err.println("bench: server did not exit; thread dump in " + out);
        } catch (IOException | InterruptedException e) {
            System.err.println("bench: could not take a thread dump: " + e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroyForcibly();
        }
    }

    // -- setup helpers --------------------------------------------------

    private void prepareRunDir() throws IOException {
        deleteRecursively(runDir.resolve("world"));
        if (config.worldSource() != null) copyDirectory(config.worldSource(), runDir.resolve("world"));
        deleteRecursively(runDir.resolve("logs"));
        deleteRecursively(runDir.resolve("crash-reports"));
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(runDir.resolve("server.properties"), serverProperties());

        deleteRecursively(runDir.resolve("mods"));
        Files.createDirectories(runDir.resolve("mods"));
        if (config.modsSourceDir() != null && Files.isDirectory(config.modsSourceDir())) {
            copyDirectory(config.modsSourceDir(), runDir.resolve("mods"));
        }
        if (config.configSourceDir() != null && Files.isDirectory(config.configSourceDir())) {
            copyDirectory(config.configSourceDir(), runDir.resolve("config"));
        }
    }

    private String serverProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("level-seed", config.levelSeed());
        props.put("level-name", "world");
        props.put("motd", "MultiForge bench");
        props.put("online-mode", "false");
        props.put("enforce-secure-profile", "false");
        props.put("spawn-protection", "0");
        props.put("max-players", String.valueOf(config.maxPlayers()));
        props.put("server-ip", "127.0.0.1");
        props.put("server-port", String.valueOf(gamePort));
        props.put("sync-chunk-writes", "true");
        props.put("enable-rcon", "true");
        props.put("rcon.password", RCON_PASSWORD);
        props.put("rcon.port", String.valueOf(rconPort));
        props.putAll(config.serverProperties());
        StringBuilder sb = new StringBuilder();
        props.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private boolean waitForBootLogPattern(String needle, Duration timeout) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(bootLog)) {
                String content = Files.readString(bootLog, StandardCharsets.UTF_8);
                if (content.contains(needle)) {
                    return true;
                }
            }
            if (!serverProcess.isAlive()) {
                return false;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    private void startRssSampler() {
        Thread rssSamplerThread = new Thread(
                () -> {
                    Path status = Path.of("/proc/" + serverProcess.pid() + "/status");
                    while (running.get()) {
                        try {
                            if (running.get() && Files.exists(status)) {
                                for (String line : Files.readAllLines(status)) {
                                    if (line.startsWith("VmRSS:")) {
                                        String digits = line.replaceAll("[^0-9]", "");
                                        if (!digits.isEmpty()) {
                                            rssPeakKb.accumulateAndGet(Long.parseLong(digits), Math::max);
                                        }
                                        break;
                                    }
                                }
                            }
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (IOException | RuntimeException ignored) {
                            // best-effort sampling only — a missed sample doesn't fail the run
                        }
                    }
                },
                "bench-rss-sampler");
        rssSamplerThread.setDaemon(true);
        rssSamplerThread.start();
    }

    private void startLogTail() {
        Thread logTailThread = new Thread(
                () -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(Files.newInputStream(bootLog), StandardCharsets.UTF_8))) {
                        while (running.get() || serverProcess.isAlive()) {
                            String line = reader.readLine();
                            if (line == null) {
                                Thread.sleep(300);
                                continue;
                            }
                            metricsSink.recordLogLine(line);
                        }
                    } catch (IOException | InterruptedException ignored) {
                        // best-effort: a missed tail read costs log-derived
                        // samples, not run correctness.
                    }
                },
                "bench-log-tail");
        logTailThread.setDaemon(true);
        logTailThread.start();
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    static void copyDirectory(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
