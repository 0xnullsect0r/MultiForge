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
package net.multiforge.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runnable installer for MultiForge — this is the jar operators
 * download from a GitHub Release and run with {@code java -jar
 * multiforge-installer-<v>.jar}. Three subcommands:
 *
 * <ul>
 *   <li>{@code install [--install-dir DIR]} — lay out a fresh MultiForge
 *       server directory. This is the Fabric-installer-shaped path a
 *       user takes on a clean box.</li>
 *   <li>{@code build-zip --out ZIP} — write the drop-in replacement zip
 *       that overlays onto an existing NeoForge server directory.</li>
 *   <li>{@code version} — print the installer version.</li>
 * </ul>
 *
 * <p>The installer bundles the runtime jar as an embedded resource so
 * it needs no separate downloads.
 */
public final class Main {

    private static final String BUNDLED_ROOT = "/net/multiforge/installer/bundle/";
    private static final String[] BUNDLED_JARS = {"multiforge-runtime.jar"};

    private Main() {}

    public static void main(String[] args) throws IOException {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0 || eq(args[0], "help", "--help", "-h")) {
            printUsage(out);
            return 0;
        }
        String cmd = args[0];
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) rest.add(args[i]);

        return switch (cmd) {
            case "install" -> doInstall(rest, out, err);
            case "build-zip" -> doBuildZip(rest, out, err);
            case "version" -> {
                out.println("MultiForge installer " + version());
                yield 0;
            }
            default -> {
                err.println("Unknown command: " + cmd);
                printUsage(err);
                yield 2;
            }
        };
    }

    // ---- install --------------------------------------------------------

    private static int doInstall(List<String> args, PrintStream out, PrintStream err) throws IOException {
        Path installDir = Path.of(".");
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--install-dir") && i + 1 < args.size()) {
                installDir = Path.of(args.get(++i));
            } else {
                err.println("install: unknown argument " + a);
                return 2;
            }
        }

        Files.createDirectories(installDir);
        Path libDir = installDir.resolve("libraries").resolve("multiforge");
        Files.createDirectories(libDir);

        for (String jar : BUNDLED_JARS) {
            byte[] jarBytes = readResource(BUNDLED_ROOT + jar);
            Files.write(libDir.resolve(jar), jarBytes);
            out.println("[installer] wrote " + libDir.resolve(jar));
        }

        Path runSh = installDir.resolve("run.sh");
        Files.writeString(runSh, runShScript());
        //noinspection ResultOfMethodCallIgnored
        runSh.toFile().setExecutable(true);
        Files.writeString(installDir.resolve("run.bat"), runBatScript());

        Path configDir = installDir.resolve("config");
        Files.createDirectories(configDir);
        Path config = configDir.resolve("multiforge-server.toml");
        if (!Files.exists(config)) {
            Files.writeString(config, defaultConfig());
            out.println("[installer] wrote " + config);
        }

        Path eula = installDir.resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(
                    eula,
                    "# Accept the Minecraft EULA at https://aka.ms/MinecraftEULA\n# by changing the line below to `eula=true`.\neula=false\n");
        }

        out.println();
        out.println("MultiForge " + version() + " installed to " + installDir.toAbsolutePath());
        out.println("Next steps:");
        out.println("  1. Set eula=true in " + eula.getFileName() + " to accept the Minecraft EULA.");
        out.println("  2. Copy your mods into ./mods and world into ./world (or let the server generate a fresh one).");
        out.println("  3. Start the server:  ./run.sh   (Windows: run.bat)");
        return 0;
    }

    // ---- build-zip ------------------------------------------------------

    private static int doBuildZip(List<String> args, PrintStream out, PrintStream err) throws IOException {
        Path outZip = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--out") && i + 1 < args.size()) {
                outZip = Path.of(args.get(++i));
            } else {
                err.println("build-zip: unknown argument " + a);
                return 2;
            }
        }
        if (outZip == null) {
            err.println("build-zip: --out ZIP is required");
            return 2;
        }
        Path parent = outZip.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outZip))) {
            for (String jar : BUNDLED_JARS) {
                addResource(zos, BUNDLED_ROOT + jar, "libraries/multiforge/" + jar);
            }
            addString(zos, "run.multiforge.sh", runShScript());
            addString(zos, "run.multiforge.bat", runBatScript());
            addString(zos, "config/multiforge-server.toml.example", defaultConfig());
            addString(zos, "README-MULTIFORGE.txt", replacementReadme());
        }
        out.println("[installer] wrote " + outZip.toAbsolutePath());
        return 0;
    }

    // ---- helpers --------------------------------------------------------

    static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private static void copyResource(String resource, Path dest) throws IOException {
        try (InputStream in = Main.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("bundled resource missing: " + resource);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readResource(String resource) throws IOException {
        try (InputStream in = Main.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("bundled resource missing: " + resource);
            return in.readAllBytes();
        }
    }

    private static void addResource(ZipOutputStream zos, String resource, String entryName) throws IOException {
        try (InputStream in = Main.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("bundled resource missing: " + resource);
            zos.putNextEntry(new ZipEntry(entryName));
            in.transferTo((OutputStream) zos);
            zos.closeEntry();
        }
    }

    private static void addString(ZipOutputStream zos, String entryName, String body) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static boolean eq(String s, String... candidates) {
        for (String c : candidates) if (c.equals(s.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static void printUsage(PrintStream out) {
        out.println("MultiForge installer " + version());
        out.println();
        out.println("Usage:");
        out.println("  java -jar multiforge-installer.jar install [--install-dir DIR]");
        out.println("  java -jar multiforge-installer.jar build-zip --out multiforge-replacement.zip");
        out.println("  java -jar multiforge-installer.jar version");
        out.println();
        out.println("`install` lays out a fresh MultiForge server in DIR (default: cwd).");
        out.println("`build-zip` writes the drop-in replacement archive you overlay on an");
        out.println("existing NeoForge server install.");
    }

    // ---- payloads -------------------------------------------------------

    private static String runShScript() {
        return """
                #!/usr/bin/env bash
                # MultiForge server launcher.
                set -euo pipefail
                cd "$(dirname "$0")"

                MEMORY="${MEMORY:-4G}"
                JVM_OPTS="${JVM_OPTS:-}"

                exec java -Xms${MEMORY} -Xmx${MEMORY} ${JVM_OPTS} \\
                    -cp "libraries/multiforge/*" \\
                    net.multiforge.runtime.bootstrap.Main "$@"
                """;
    }

    private static String runBatScript() {
        return """
                @echo off
                REM MultiForge server launcher.
                cd /d "%~dp0"
                if "%MEMORY%"=="" set MEMORY=4G

                java -Xms%MEMORY% -Xmx%MEMORY% %JVM_OPTS% ^
                    -cp "libraries\\multiforge\\*" ^
                    net.multiforge.runtime.bootstrap.Main %*
                """;
    }

    private static String defaultConfig() {
        return """
                # MultiForge server config. Reloadable via /multiforge config reload.
                cores = 8
                threads-per-core = 2

                [regions]
                size = 16
                mode = "player-only"       # or "full-world"
                mspt-split-threshold = 30.0
                mspt-merge-threshold = 5.0

                [persistence]
                autosave-per-tick-chunks = 8
                autosave-per-tick-nanos  = 2000000
                journal-fsync = true

                [diagnostics]
                warn-per-mod-per-second = 5
                debug-channel-enabled = true
                """;
    }

    private static String replacementReadme() {
        return """
                MultiForge — drop-in replacement bundle
                =======================================

                Overlay this archive onto an existing NeoForge 1.21.1 dedicated
                server directory. Files land in place beside your existing world,
                mods, and configs.

                What lands where:

                    libraries/multiforge/multiforge-runtime.jar   ← the runtime library
                    run.multiforge.sh                             ← Linux/macOS launcher (rename to run.sh once ready)
                    run.multiforge.bat                            ← Windows launcher (rename to run.bat once ready)
                    config/multiforge-server.toml.example         ← default config (rename to multiforge-server.toml)

                Migration steps:

                    1. Stop the running NeoForge server (/stop, wait for "Saving...").
                    2. Back up your world:  tar czf backup.tgz world/ mods/ config/
                    3. Overlay this archive:  unzip multiforge-<version>-replacement.zip
                    4. Rename run.multiforge.sh → run.sh (backing up your existing run.sh first).
                    5. Rename config/multiforge-server.toml.example → config/multiforge-server.toml
                       and edit cores / threads-per-core to match your machine.
                    6. Start the server:  ./run.sh

                Rolling back: MultiForge writes only to world/multiforge/, config/multiforge-server.toml,
                and its own logs/multiforge-*.log. Delete those + restore your old run.sh + drop your
                original NeoForge server jar back in place. The world is byte-compatible with upstream
                NeoForge.

                Docs: https://github.com/0xnullsect0r/multiforge/tree/main/docs
                Support: https://github.com/0xnullsect0r/multiforge/issues
                """;
    }
}
