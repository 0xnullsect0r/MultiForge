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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * An installed server directory: the output of {@code java -jar
 * <installer>.jar --installServer <dir>}, either the MultiForge installer
 * built by {@code :neoforge:installerJar} or a stock NeoForge installer for
 * baseline runs. The bench boots the JVM itself from the installer's
 * {@code unix_args.txt} rather than through {@code run.sh}, so it owns the
 * process (pid, exit, kill) and can add {@code -D} flags.
 */
public final class ServerInstall {
    private static final String MARKER = ".mfbench-installer";

    private final Path dir;
    private final Path argsFile;

    private ServerInstall(Path dir, Path argsFile) {
        this.dir = dir;
        this.argsFile = argsFile;
    }

    public Path dir() {
        return dir;
    }

    /**
     * Install {@code installerJar} into {@code dir} unless {@code dir} already
     * holds an install of that same installer (recorded by file name and size).
     * A different installer wipes the libraries so a stale NeoForge build
     * cannot linger.
     */
    public static ServerInstall ensure(Path installerJar, Path dir, Path logFile)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(installerJar)) {
            throw new IOException("installer jar not found: " + installerJar);
        }
        String identity = installerJar.getFileName() + " " + Files.size(installerJar) + " "
                + Files.getLastModifiedTime(installerJar).toMillis();
        Path marker = dir.resolve(MARKER);
        Optional<ServerInstall> existing = open(dir);
        if (existing.isPresent()
                && Files.isRegularFile(marker)
                && Files.readString(marker).strip().equals(identity)) {
            return existing.get();
        }
        Files.createDirectories(dir);
        HeadlessServerRunner.deleteRecursively(dir.resolve("libraries"));
        if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
        Process p = new ProcessBuilder(List.of(
                        javaExecutable(),
                        "-jar",
                        installerJar.toAbsolutePath().toString(),
                        "--installServer",
                        dir.toAbsolutePath().toString()))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("installer exited " + exit + " — see " + logFile);
        Files.writeString(marker, identity + "\n", StandardCharsets.UTF_8);
        return open(dir).orElseThrow(() -> new IOException("installer left no unix_args.txt under " + dir));
    }

    /** An existing install at {@code dir}, if it has a NeoForge {@code unix_args.txt}. */
    public static Optional<ServerInstall> open(Path dir) throws IOException {
        Path root = dir.resolve("libraries/net/neoforged/neoforge");
        if (!Files.isDirectory(root)) return Optional.empty();
        try (Stream<Path> versions = Files.list(root)) {
            return versions.map(v -> v.resolve("unix_args.txt"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .reduce((a, b) -> b)
                    .map(args -> new ServerInstall(dir, dir.relativize(args)));
        }
    }

    /** The launch command, before any program arguments: {@code java <jvmArgs> @user_jvm_args.txt @unix_args.txt}. */
    public List<String> command(List<String> jvmArgs) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaExecutable());
        cmd.addAll(jvmArgs);
        if (Files.isRegularFile(dir.resolve("user_jvm_args.txt"))) cmd.add("@user_jvm_args.txt");
        cmd.add("@" + argsFile);
        cmd.add("nogui");
        return cmd;
    }

    /** {@code $JAVA_HOME/bin/java} when set, else {@code java} from {@code PATH}. */
    static String javaExecutable() {
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            Path java = Path.of(home, "bin", "java");
            if (Files.isExecutable(java)) return java.toString();
        }
        return "java";
    }
}
