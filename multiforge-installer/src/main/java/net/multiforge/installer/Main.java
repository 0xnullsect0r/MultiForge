/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
 *   <li>{@code install [--install-dir DIR] [--license TOKEN|@FILE]} —
 *       lay out a fresh MultiForge server directory. This is the
 *       Fabric-installer-shaped path a customer uses on a clean box.</li>
 *   <li>{@code build-zip --out ZIP} — write the drop-in replacement zip
 *       that overlays onto an existing NeoForge server directory.</li>
 *   <li>{@code version} — print the installer version.</li>
 * </ul>
 *
 * <p>The installer bundles the runtime + license verifier jars as
 * embedded resources so it needs no separate downloads. Both jars are
 * written to disk intact — an operator can swap either one out
 * independently later.
 */
public final class Main {

    private static final String BUNDLED_ROOT = "/net/multiforge/installer/bundle/";
    private static final String[] BUNDLED_JARS = {
        "multiforge-runtime.jar", "multiforge-license.jar",
    };

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
        String licenseArg = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--install-dir") && i + 1 < args.size()) {
                installDir = Path.of(args.get(++i));
            } else if (a.equals("--license") && i + 1 < args.size()) {
                licenseArg = args.get(++i);
            } else {
                err.println("install: unknown argument " + a);
                return 2;
            }
        }

        Files.createDirectories(installDir);
        Path libDir = installDir.resolve("libraries").resolve("multiforge");
        Files.createDirectories(libDir);

        // 1. Extract bundled jars.
        for (String jar : BUNDLED_JARS) {
            copyResource(BUNDLED_ROOT + jar, libDir.resolve(jar));
            out.println("[installer] wrote " + libDir.resolve(jar));
        }

        // 2. Write launcher scripts.
        Path runSh = installDir.resolve("run.sh");
        Files.writeString(runSh, runShScript());
        //noinspection ResultOfMethodCallIgnored
        runSh.toFile().setExecutable(true);
        Files.writeString(installDir.resolve("run.bat"), runBatScript());

        // 3. Default MultiForge config.
        Path configDir = installDir.resolve("config");
        Files.createDirectories(configDir);
        Path config = configDir.resolve("multiforge-server.toml");
        if (!Files.exists(config)) {
            Files.writeString(config, defaultConfig());
            out.println("[installer] wrote " + config);
        }

        // 4. Empty eula.txt / license.key with clear headers.
        Path eula = installDir.resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(
                    eula,
                    "# Accept the Minecraft EULA at https://aka.ms/MinecraftEULA\n# by changing the line below to `eula=true`.\neula=false\n");
        }

        Path licenseKey = installDir.resolve("license.key");
        if (licenseArg != null) {
            String token = licenseArg.startsWith("@")
                    ? Files.readString(Path.of(licenseArg.substring(1))).trim()
                    : licenseArg.trim();
            Files.writeString(licenseKey, token + "\n");
            //noinspection ResultOfMethodCallIgnored
            licenseKey.toFile().setReadable(false, false);
            //noinspection ResultOfMethodCallIgnored
            licenseKey.toFile().setReadable(true, true);
            out.println("[installer] wrote " + licenseKey + " (mode 600)");
        } else if (!Files.exists(licenseKey)) {
            Files.writeString(
                    licenseKey, "# Paste your MultiForge license token on the next line, then delete this comment.\n");
        }

        out.println();
        out.println("MultiForge " + version() + " installed to " + installDir.toAbsolutePath());
        out.println("Next steps:");
        out.println("  1. Set eula=true in " + eula.getFileName() + " to accept the Minecraft EULA.");
        out.println("  2. Paste your license token into " + licenseKey.getFileName()
                + " (or set MULTIFORGE_LICENSE in the environment).");
        out.println("  3. Copy your mods into ./mods and world into ./world (or let the server generate a fresh one).");
        out.println("  4. Start the server:  ./run.sh   (Windows: run.bat)");
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
            // libraries/multiforge/*.jar
            for (String jar : BUNDLED_JARS) {
                addResource(zos, BUNDLED_ROOT + jar, "libraries/multiforge/" + jar);
            }
            // run.sh, run.bat — the operator's existing run scripts stay
            // in place; ours are additive so they can compare.
            addString(zos, "run.multiforge.sh", runShScript());
            addString(zos, "run.multiforge.bat", runBatScript());
            // Default config — the operator's existing config directory
            // is preserved; ours drops in an example alongside it.
            addString(zos, "config/multiforge-server.toml.example", defaultConfig());
            // Readme so the operator knows what's what.
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
        out.println("  java -jar multiforge-installer.jar install [--install-dir DIR] [--license TOKEN|@FILE]");
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

                # Read license.key if MULTIFORGE_LICENSE is not already set.
                if [ -z "${MULTIFORGE_LICENSE:-}" ] && [ -f license.key ]; then
                    LICENSE_LINE="$(grep -v '^#' license.key | head -n1 | tr -d '\\r\\n')"
                    if [ -n "$LICENSE_LINE" ]; then export MULTIFORGE_LICENSE="$LICENSE_LINE"; fi
                fi

                exec java -Xms${MEMORY} -Xmx${MEMORY} ${JVM_OPTS} \\
                    -Dmultiforge.license="${MULTIFORGE_LICENSE:-}" \\
                    -cp "libraries/multiforge/*" \\
                    net.multiforge.runtime.bootstrap.LicenseOnlyMain "$@"
                """;
    }

    private static String runBatScript() {
        return """
                @echo off
                REM MultiForge server launcher.
                cd /d "%~dp0"
                if "%MEMORY%"=="" set MEMORY=4G

                if "%MULTIFORGE_LICENSE%"=="" (
                    if exist license.key (
                        for /f "usebackq delims=" %%L in ("license.key") do (
                            echo %%L | findstr /b "#" >nul || (set MULTIFORGE_LICENSE=%%L & goto :got_license)
                        )
                        :got_license
                    )
                )

                java -Xms%MEMORY% -Xmx%MEMORY% %JVM_OPTS% ^
                    -Dmultiforge.license="%MULTIFORGE_LICENSE%" ^
                    -cp "libraries\\multiforge\\*" ^
                    net.multiforge.runtime.bootstrap.LicenseOnlyMain %*
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
                    libraries/multiforge/multiforge-license.jar   ← the license verifier
                    run.multiforge.sh                             ← Linux/macOS launcher (rename to run.sh once ready)
                    run.multiforge.bat                            ← Windows launcher (rename to run.bat once ready)
                    config/multiforge-server.toml.example         ← default config (rename to multiforge-server.toml)

                Migration steps:

                    1. Stop the running NeoForge server (/stop, wait for "Saving...").
                    2. Back up your world:  tar czf backup.tgz world/ mods/ config/
                    3. Overlay this archive:  unzip multiforge-<version>-replacement.zip
                    4. Move your license token into ./license.key (one line, chmod 600).
                       Or set the MULTIFORGE_LICENSE env var.
                    5. Rename run.multiforge.sh → run.sh (backing up your existing run.sh first).
                    6. Rename config/multiforge-server.toml.example → config/multiforge-server.toml
                       and edit cores / threads-per-core to match your machine.
                    7. Start the server:  ./run.sh

                Rolling back: MultiForge writes only to world/multiforge/, config/multiforge-server.toml,
                and its own logs/multiforge-*.log. Delete those + restore your old run.sh + drop your
                original NeoForge server jar back in place. The world is byte-compatible with upstream
                NeoForge.

                Docs: https://github.com/0xnullsect0r/multiforge/tree/main/docs
                Support: https://github.com/0xnullsect0r/multiforge/issues
                """;
    }
}
