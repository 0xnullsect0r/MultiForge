/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.bench.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Boots one headless MultiForge dev server — the vendored, patched
 * NeoForge fork under {@code upstream/neoforge-1.21.1} — drives it over
 * RCON, and tears it down.
 *
 * <p>This is the Java port of the shell-script pattern proven out by
 * hand at {@code scratchpad/capture.sh} while investigating the Phase
 * 7.2/7.3 determinism captures: run {@code ./gradlew :neoforge:runServer
 * -Dmultiforge.workers=N}, wait for {@code "RCON running on"} in the
 * boot log, drive it via RCON, {@code save-all flush} then {@code stop},
 * wait for the child JVM to exit (and force-kill it if it doesn't).
 *
 * <p>Talks to the server exclusively over TCP (RCON) and by tailing its
 * log file — no {@code net.minecraft.*} import anywhere in this class,
 * per CLAUDE.md's requirement that {@code multiforge-bench} stay
 * MC-free.
 */
public final class HeadlessServerRunner implements AutoCloseable {

    private static final Duration RCON_BOOT_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration RCON_IO_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration JVM_EXIT_TIMEOUT = Duration.ofSeconds(90);
    private static final Path DEV_JDK = Path.of("/home/aric/.local/jdk/jdk-21.0.12.1+1");
    private static final String RCON_PASSWORD = "multiforge";
    private static final int RCON_PORT = 25575;

    /**
     * @param neoforgeWorkspaceDir path to {@code upstream/neoforge-1.21.1}
     * @param workers value passed as {@code -Dmultiforge.workers=}
     * @param levelSeed fixed seed for reproducible captures
     * @param maxPlayers {@code server.properties} {@code max-players}
     * @param modsSourceDir if non-null, copied into the run dir's {@code mods/} (atm10 profile)
     * @param configSourceDir if non-null, copied into the run dir's {@code config/} (atm10 profile)
     * @param extraJvmArgs extra whitespace-separated {@code -D}/{@code -X} flags appended to the launch command
     */
    public record Config(
            Path neoforgeWorkspaceDir,
            int workers,
            String levelSeed,
            int maxPlayers,
            Path modsSourceDir,
            Path configSourceDir,
            String extraJvmArgs) {}

    private final Config config;
    private final Path runDir;
    private final Path bootLog;

    private Process gradleProcess;
    private RconClient rcon;
    private long serverPid = -1;
    private MetricsCollector metricsSink;

    private final AtomicLong heapPeakKb = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread rssSamplerThread;
    private Thread logTailThread;

    public HeadlessServerRunner(Config config, Path bootLogPath) {
        this.config = config;
        this.runDir = config.neoforgeWorkspaceDir().resolve("projects/neoforge/run/server");
        this.bootLog = bootLogPath;
    }

    /**
     * Boots the server. Returns {@code false} (rather than throwing) if
     * RCON never comes up within {@link #RCON_BOOT_TIMEOUT} — a boot
     * failure is a legitimate bench outcome ({@code boot_ok: false} in
     * the result JSON), not necessarily a harness bug.
     */
    public boolean boot(MetricsCollector metricsSink) throws IOException, InterruptedException {
        this.metricsSink = metricsSink;
        if (bootLog.getParent() != null) {
            Files.createDirectories(bootLog.getParent());
        }
        prepareRunDir();

        List<String> cmd = new ArrayList<>();
        cmd.add(config.neoforgeWorkspaceDir()
                .resolve("gradlew")
                .toAbsolutePath()
                .toString());
        cmd.add(":neoforge:runServer");
        cmd.add("-Dmultiforge.workers=" + config.workers());
        if (config.extraJvmArgs() != null && !config.extraJvmArgs().isBlank()) {
            for (String part : config.extraJvmArgs().trim().split("\\s+")) {
                cmd.add(part);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(config.neoforgeWorkspaceDir().toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(bootLog.toFile());

        var env = pb.environment();
        env.put("MULTIFORGE_LICENSE", licenseKeyContents());
        // Force, not putIfAbsent: this is a nested `gradlew
        // :neoforge:runServer` invocation, and a GRADLE_OPTS already set
        // in the environment this JVM was launched from (e.g. the outer
        // `./gradlew :multiforge-bench:vanilla` invocation's own shell)
        // would otherwise leak straight through to it — confirmed live
        // during Phase 7.4a implementation to starve the nested build's
        // daemon (3 GiB was not enough for the NeoForge/NeoForm build
        // graph) even though the outer build ran fine on it.
        env.put("GRADLE_OPTS", "-Xmx6G");
        if (Files.isDirectory(DEV_JDK)) {
            env.put("JAVA_HOME", DEV_JDK.toString());
            env.put("PATH", DEV_JDK.resolve("bin") + ":" + env.getOrDefault("PATH", ""));
        }

        running.set(true);
        gradleProcess = pb.start();

        boolean rconUp = waitForBootLogPattern("RCON running on", RCON_BOOT_TIMEOUT);
        if (!rconUp) {
            running.set(false);
            return false;
        }

        serverPid = findServerJvmPid().orElse(-1L);
        startRssSampler();
        startLogTail();

        rcon = new RconClient("127.0.0.1", RCON_PORT, RCON_PASSWORD, RCON_IO_TIMEOUT);
        // The RCON bind-log line lands a beat before the socket reliably
        // accepts+auths a client in practice; probe with a real command
        // (rather than a blind sleep) until it succeeds or times out.
        long probeDeadline = System.currentTimeMillis() + 15_000;
        boolean rconReady = false;
        IOException lastError = null;
        while (System.currentTimeMillis() < probeDeadline) {
            try {
                rcon.command("list");
                rconReady = true;
                break;
            } catch (IOException e) {
                lastError = e;
                Thread.sleep(500);
            }
        }
        if (!rconReady) {
            running.set(false);
            throw new IOException("RCON never became responsive after boot", lastError);
        }
        return true;
    }

    public RconClient rcon() {
        return rcon;
    }

    public long serverPid() {
        return serverPid;
    }

    public long heapPeakMb() {
        return heapPeakKb.get() / 1024;
    }

    /**
     * Freezes the tick loop, sprints exactly {@code ticks} game-ticks via
     * {@code /tick sprint}, and waits for the resulting "Sprint
     * completed" (or "Nothing to sprint") log line.
     *
     * <p><b>Why sprint, not real time:</b> {@code /tick sprint} runs the
     * requested ticks back-to-back with the normal 50ms-per-tick pacing
     * disabled, so a 12000-tick (10 game-minute) run typically finishes
     * in well under a second of wall-clock time on an idle world. The
     * MSPT numbers it produces still measure genuine per-tick
     * computation cost — {@code ServerTickRateManager} times each tick
     * the same way whether or not it then sleeps to pace to 20 TPS — so
     * this is a fast, repeatable way to gather the compute-cost
     * distribution ({@link MetricsCollector}) without waiting out 10
     * real minutes for an idle vanilla or ATM10-idle-boot profile. The
     * swarm profile instead needs a real sustained-load window (see
     * {@code SwarmBench}'s churn loop) since its whole point is whether
     * concurrent load holds 20 TPS over time, not raw per-tick cost.
     */
    public void runSprintProfile(long ticks) throws IOException, InterruptedException {
        rcon.command("tick freeze");
        rcon.command("tick sprint " + ticks);

        long timeoutMs = Math.max(30_000, ticks / 5);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (metricsSink.sawSprintCompleted()) {
                break;
            }
            if (!gradleProcess.isAlive()) {
                break;
            }
            Thread.sleep(200);
        }
        // One more query while frozen post-sprint, to capture the
        // last-~100-tick rolling-window percentiles at the tail of the run.
        safeQuery();
    }

    /** Issues one {@code /tick query} and feeds the reply to the metrics sink; failures are swallowed. */
    public void safeQuery() {
        try {
            String reply = rcon.command("tick query");
            metricsSink.recordTickQueryReply(reply);
        } catch (IOException e) {
            // Best-effort: RCON can be briefly unresponsive while the
            // server thread is mid-sprint. A missed sample just means one
            // fewer percentile data point, not a failed run.
        }
    }

    /**
     * Flushes and stops the server, then waits for the child JVM to
     * exit, force-killing it if it doesn't within {@link
     * #JVM_EXIT_TIMEOUT}.
     *
     * @return {@code true} if {@code save-all}/{@code stop} both
     *     round-tripped over RCON and the JVM exited on its own.
     */
    public boolean shutdown(Duration settleBeforeStop) throws InterruptedException {
        boolean cleanStop = true;
        try {
            if (settleBeforeStop != null) {
                Thread.sleep(settleBeforeStop.toMillis());
            }
            rcon.command("save-all flush");
            Thread.sleep(2000);
            rcon.command("stop");
        } catch (IOException e) {
            cleanStop = false;
        }

        long deadline = System.currentTimeMillis() + JVM_EXIT_TIMEOUT.toMillis();
        while (isServerAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000);
        }
        if (isServerAlive()) {
            cleanStop = false;
            ProcessHandle.of(serverPid).ifPresent(ProcessHandle::destroy);
            Thread.sleep(3000);
            ProcessHandle.of(serverPid).ifPresent(ProcessHandle::destroyForcibly);
        }

        running.set(false);
        if (gradleProcess != null) {
            gradleProcess.waitFor(10, TimeUnit.SECONDS);
        }
        return cleanStop;
    }

    private boolean isServerAlive() {
        return serverPid > 0
                && ProcessHandle.of(serverPid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Override
    public void close() {
        running.set(false);
        // RconClient opens/closes a fresh connection per command — see
        // its class javadoc — so there is no persistent socket to close
        // here.
        if (isServerAlive()) {
            ProcessHandle.of(serverPid).ifPresent(ProcessHandle::destroyForcibly);
        }
        if (gradleProcess != null && gradleProcess.isAlive()) {
            gradleProcess.destroyForcibly();
        }
    }

    // -- setup helpers --------------------------------------------------

    private void prepareRunDir() throws IOException {
        deleteRecursively(runDir.resolve("world"));
        deleteRecursively(runDir.resolve("logs"));
        deleteRecursively(runDir.resolve("crash-reports"));
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(runDir.resolve("server.properties"), serverProperties());

        Path license = licenseKeyPath();
        if (Files.exists(license)) {
            Files.copy(license, runDir.resolve("license.key"), StandardCopyOption.REPLACE_EXISTING);
        }

        if (config.modsSourceDir() != null && Files.isDirectory(config.modsSourceDir())) {
            deleteRecursively(runDir.resolve("mods"));
            copyDirectory(config.modsSourceDir(), runDir.resolve("mods"));
        }
        if (config.configSourceDir() != null && Files.isDirectory(config.configSourceDir())) {
            deleteRecursively(runDir.resolve("config"));
            copyDirectory(config.configSourceDir(), runDir.resolve("config"));
        }
    }

    private String serverProperties() {
        return "level-seed=" + config.levelSeed() + "\n"
                + "level-name=world\n"
                + "motd=MultiForge bench\n"
                + "online-mode=false\n"
                + "spawn-protection=0\n"
                + "max-players=" + config.maxPlayers() + "\n"
                + "sync-chunk-writes=true\n"
                + "enable-rcon=true\n"
                + "rcon.password=" + RCON_PASSWORD + "\n"
                + "rcon.port=" + RCON_PORT + "\n";
    }

    private static Path licenseKeyPath() {
        return Path.of(System.getProperty("user.home"), ".multiforge", "license.key");
    }

    private String licenseKeyContents() throws IOException {
        Path license = licenseKeyPath();
        if (!Files.exists(license)) {
            throw new IOException("no license key at " + license + " — a MultiForge dev server needs one to boot; "
                    + "see docs/design/m9-phase7-runbook.md §1");
        }
        return Files.readString(license, StandardCharsets.UTF_8).trim();
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
            if (!gradleProcess.isAlive()) {
                return false;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    /**
     * Finds the actual server JVM's pid by scanning {@code /proc/*}/cmdline}
     * directly for one whose argv names both the ModLauncher bootstrap
     * and this run's own {@code --gameDir}.
     *
     * <p>Gradle's daemon forks the {@code :neoforge:runServer} JVM as a
     * descendant of the long-lived Gradle Daemon process, not of the
     * {@code gradlew} subprocess this class starts, so walking {@code
     * gradleProcess}'s own children wouldn't find it — a system-wide
     * scan is unavoidable.
     *
     * <p>This reads {@code /proc} directly rather than using {@code
     * ProcessHandle.Info.commandLine()}: that API truncates at 4096
     * characters on Linux (confirmed live during Phase 7.4a
     * implementation — this server's real command line, with its full
     * NeoForge/mod classpath, is comfortably longer than that, and both
     * the {@code BootstrapLauncher} main-class token and the {@code
     * --gameDir} value land past the truncation point, past every mod
     * jar and library on the classpath). Matching on the bootstrap class
     * name alone is not enough either: it also matches any unrelated
     * Minecraft client/server on the same machine using the same
     * launcher (this dev box also runs a CurseForge-launched client that
     * trips the same substring), so the match additionally requires this
     * run's own {@link #runDir}, which every {@code runServer}
     * invocation passes as {@code --gameDir} and which is unique per
     * {@link HeadlessServerRunner} instance.
     */
    private Optional<Long> findServerJvmPid() {
        String gameDirMarker = runDir.toAbsolutePath().toString();
        Path procRoot = Path.of("/proc");
        if (!Files.isDirectory(procRoot)) {
            // Not Linux (or no /proc) — this harness targets the Linux
            // dev box CLAUDE.md describes; RSS sampling is best-effort
            // and simply won't have a pid to sample elsewhere.
            return Optional.empty();
        }
        try (Stream<Path> pidDirs = Files.list(procRoot)) {
            return pidDirs.filter(p -> p.getFileName().toString().chars().allMatch(Character::isDigit))
                    .filter(p -> {
                        List<String> args = readCmdlineArgs(p.resolve("cmdline"));
                        boolean hasBootstrap =
                                args.stream().anyMatch(a -> a.contains("bootstraplauncher.BootstrapLauncher"));
                        boolean hasGameDir = args.stream().anyMatch(a -> a.equals(gameDirMarker));
                        return hasBootstrap && hasGameDir;
                    })
                    .map(p -> Long.parseLong(p.getFileName().toString()))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Reads {@code /proc/<pid>/cmdline}'s NUL-separated argv, or an empty list if it can't be read (process gone, permission). */
    private static List<String> readCmdlineArgs(Path cmdlinePath) {
        try {
            byte[] raw = Files.readAllBytes(cmdlinePath);
            List<String> args = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == 0) {
                    args.add(new String(raw, start, i - start, StandardCharsets.UTF_8));
                    start = i + 1;
                }
            }
            if (start < raw.length) {
                args.add(new String(raw, start, raw.length - start, StandardCharsets.UTF_8));
            }
            return args;
        } catch (IOException e) {
            return List.of();
        }
    }

    private void startRssSampler() {
        if (serverPid <= 0) {
            return;
        }
        rssSamplerThread = new Thread(
                () -> {
                    Path status = Path.of("/proc/" + serverPid + "/status");
                    while (running.get()) {
                        try {
                            if (Files.exists(status)) {
                                for (String line : Files.readAllLines(status)) {
                                    if (line.startsWith("VmRSS:")) {
                                        String digits = line.replaceAll("[^0-9]", "");
                                        if (!digits.isEmpty()) {
                                            heapPeakKb.accumulateAndGet(Long.parseLong(digits), Math::max);
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
        logTailThread = new Thread(
                () -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(Files.newInputStream(bootLog), StandardCharsets.UTF_8))) {
                        while (running.get() || gradleProcess.isAlive()) {
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

    private static void deleteRecursively(Path dir) throws IOException {
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

    private static void copyDirectory(Path src, Path dst) throws IOException {
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
