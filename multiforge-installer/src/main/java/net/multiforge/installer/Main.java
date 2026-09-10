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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Build tool for the Method 2 drop-in replacement archive. This is
 * <em>not</em> the jar operators download — that is the fork installer
 * (a NeoForge-format installer carrying the MultiForge patches),
 * published as {@code multiforge-installer.jar}. This class only
 * assembles the archive that wraps it.
 *
 * <h2>Why the archive wraps an installer</h2>
 *
 * <p>Through v1.4.1 the archive shipped {@code multiforge-runtime.jar}
 * plus a launcher that ran {@code net.multiforge.runtime.bootstrap.Main}
 * — a class that has never existed in this codebase. Even had it
 * existed, the launcher's classpath was {@code libraries/multiforge/*},
 * one Minecraft-free library jar: no Minecraft, no NeoForge, no
 * ModLauncher. It could not boot a server, and the premise was wrong
 * besides. MultiForge is a <em>fork</em> — the regionized tick loop
 * lives in patches to {@code net.minecraft.*} and {@code
 * net.neoforged.*} classes that ship inside the patched NeoForge jar.
 * Dropping a library beside a stock NeoForge server leaves the stock,
 * unpatched server running.
 *
 * <p>The archive also cannot simply ship the finished install tree. A
 * completed server install is ~180&nbsp;MB and contains Mojang's
 * {@code server-1.21.1.jar} together with the patched derivatives
 * ({@code -srg}, {@code -slim}, {@code -extra}, {@code -unpacked})
 * produced from it. Redistributing those is not permitted, which is
 * precisely why NeoForge — and Forge before it — ship an installer that
 * downloads from Mojang and applies binary patches on the user's own
 * machine.
 *
 * <p>So the archive embeds the fork installer and a wrapper script that
 * runs it in place against an existing server directory, then leaves
 * behind a launcher with a JDK-21 preflight. That is a genuine in-place
 * conversion, and it is the only lawful shape for one.
 *
 * <p>Subcommands:
 *
 * <ul>
 *   <li>{@code build-zip --out ZIP --installer FORK_INSTALLER_JAR} —
 *       assemble the drop-in archive around the given fork installer.
 *   <li>{@code version} — print the version.
 * </ul>
 */
public final class Main {

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

    // ---- build-zip ------------------------------------------------------

    /** Where the fork installer lands inside the archive. */
    static final String INSTALLER_ENTRY = "multiforge/multiforge-installer.jar";

    private static int doBuildZip(List<String> args, PrintStream out, PrintStream err) throws IOException {
        Path outZip = null;
        Path forkInstaller = null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--out") && i + 1 < args.size()) {
                outZip = Path.of(args.get(++i));
            } else if (a.equals("--installer") && i + 1 < args.size()) {
                forkInstaller = Path.of(args.get(++i));
            } else {
                err.println("build-zip: unknown argument " + a);
                return 2;
            }
        }
        if (outZip == null) {
            err.println("build-zip: --out ZIP is required");
            return 2;
        }
        if (forkInstaller == null) {
            err.println("build-zip: --installer FORK_INSTALLER_JAR is required");
            err.println("  Pass the NeoForge-format fork installer built by");
            err.println("  :neoforge:build under upstream/neoforge-1.21.1 — the archive");
            err.println("  embeds it and runs it against the operator's server directory.");
            return 2;
        }
        if (!Files.isRegularFile(forkInstaller)) {
            err.println("build-zip: no such installer jar: " + forkInstaller);
            return 2;
        }

        Path parent = outZip.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outZip))) {
            addFile(zos, forkInstaller, INSTALLER_ENTRY);
            addString(zos, "install-multiforge.sh", installShScript());
            addString(zos, "install-multiforge.bat", installBatScript());
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

    private static void addFile(ZipOutputStream zos, Path file, String entryName) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo((OutputStream) zos);
        }
        zos.closeEntry();
    }

    private static void addString(ZipOutputStream zos, String entryName, String body) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(body.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static boolean eq(String s, String... candidates) {
        for (String c : candidates) if (c.equals(s.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static void printUsage(PrintStream out) {
        out.println("MultiForge replacement-archive builder " + version());
        out.println();
        out.println("Usage:");
        out.println("  java -jar multiforge-installer.jar build-zip \\");
        out.println("      --out multiforge-replacement.zip \\");
        out.println("      --installer path/to/neoforge-<v>-installer.jar");
        out.println("  java -jar multiforge-installer.jar version");
        out.println();
        out.println("This is a build tool, not the operator-facing installer. It");
        out.println("assembles the drop-in archive around the fork installer, which is");
        out.println("what actually converts a server directory.");
    }

    // ---- payloads -------------------------------------------------------

    /**
     * The in-place converter shipped inside the archive.
     *
     * <p>Note the unquoted {@code <<PREFLIGHT_FAIL} heredoc delimiters
     * below. v1.3.18's preflight used a <em>quoted</em> delimiter, which
     * suppresses expansion — so the message that was added specifically
     * to name the offending JDK printed the literal text {@code
     * ${JAVA_MAJOR:-unknown}} instead of the version it had just
     * detected.
     */
    static String installShScript() {
        return """
                #!/usr/bin/env bash
                # MultiForge in-place converter.
                #
                # Run this from inside an existing NeoForge 1.21.1 dedicated server
                # directory. It converts that directory to MultiForge, leaving your
                # world, mods, and configs untouched.
                set -euo pipefail
                cd "$(dirname "$0")"

                say() { printf '\\033[1;36m[multiforge]\\033[0m %s\\n' "$*"; }
                fail() { printf '\\033[1;31m[multiforge]\\033[0m %s\\n' "$*" >&2; exit 1; }

                # ---- 1. JDK 21 preflight -------------------------------------------
                if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
                    JAVA_BIN="$JAVA_HOME/bin/java"
                else
                    JAVA_BIN="$(command -v java || true)"
                fi
                [ -n "$JAVA_BIN" ] || fail "No java on PATH and JAVA_HOME is unset. Install Temurin 21."

                JAVA_MAJOR=$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ { split($2, a, "."); print a[1]; exit }')
                if [ "${JAVA_MAJOR:-0}" != "21" ]; then
                    cat >&2 <<PREFLIGHT_FAIL
                MultiForge requires JDK 21. Found: ${JAVA_MAJOR:-unknown} at $JAVA_BIN

                NeoForge 1.21.1 targets JDK 21, and any 1.21.1 modpack bundling SpongeMixin
                (ATM10, ATM9, most kitchen-sink packs) crashes at mod-scan on JDK 22+ with
                "Unsupported class file major version 7X" — 70 = JDK 26, 69 = 25, 68 = 24.

                Install Temurin 21, then either:
                    export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk
                    JAVA_HOME=/path/to/jdk-21 ./install-multiforge.sh
                PREFLIGHT_FAIL
                    exit 1
                fi
                say "JDK 21 at $JAVA_BIN"

                # ---- 2. Sanity-check the target directory --------------------------
                if [ ! -d libraries ] && [ ! -f run.sh ] && [ ! -f server.properties ]; then
                    say "WARNING: this does not look like an existing server directory."
                    say "         (no libraries/, run.sh, or server.properties here)"
                    say "         Continuing — the installer will lay out a fresh server."
                fi

                # ---- 3. Back up the existing launcher ------------------------------
                STAMP="$(date +%Y%m%d-%H%M%S)"
                for f in run.sh run.bat user_jvm_args.txt; do
                    if [ -f "$f" ]; then
                        cp -p "$f" "$f.pre-multiforge-$STAMP.bak"
                        say "backed up $f -> $f.pre-multiforge-$STAMP.bak"
                    fi
                done

                # ---- 4. Convert -----------------------------------------------------
                say "running the MultiForge installer (downloads Minecraft + libraries on first run)"
                "$JAVA_BIN" -jar multiforge/multiforge-installer.jar --installServer .

                # ---- 5. Wrap the generated launcher with the same preflight ---------
                # The installer writes a stock NeoForge run.sh that calls bare `java`
                # with no version check, so a correct install still dies at mod-scan
                # if the operator's default JDK is wrong. Wrap it.
                if [ -f run.sh ]; then
                    mv run.sh run.multiforge-args.sh
                    cat > run.sh <<'LAUNCHER'
                #!/usr/bin/env sh
                # MultiForge server launcher (JDK 21 preflight + NeoForge args).
                cd "$(dirname "$0")"

                if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
                    JAVA_BIN="$JAVA_HOME/bin/java"
                else
                    JAVA_BIN="$(command -v java || true)"
                fi
                if [ -z "$JAVA_BIN" ]; then
                    echo "MultiForge: no java on PATH and JAVA_HOME is unset. Install Temurin 21." >&2
                    exit 1
                fi
                JAVA_MAJOR=$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ { split($2, a, "."); print a[1]; exit }')
                if [ "${JAVA_MAJOR:-0}" != "21" ]; then
                    cat >&2 <<PREFLIGHT
                MultiForge requires JDK 21. Found: ${JAVA_MAJOR:-unknown} at $JAVA_BIN
                Point JAVA_HOME at a JDK 21 install (Temurin 21) and retry.
                PREFLIGHT
                    exit 1
                fi

                exec "$JAVA_BIN" @user_jvm_args.txt @ARGS_FILE "$@"
                LAUNCHER
                    # Splice in the args file the installer chose for this version.
                    ARGS_LINE="$(grep -o '@libraries/[^ ]*' run.multiforge-args.sh | head -1)"
                    [ -n "$ARGS_LINE" ] || fail "could not find the NeoForge args file in the generated run.sh"
                    sed -i.tmp "s|@ARGS_FILE|$ARGS_LINE|" run.sh && rm -f run.sh.tmp
                    rm -f run.multiforge-args.sh
                    chmod +x run.sh
                    say "wrote run.sh (JDK 21 preflight + $ARGS_LINE)"
                fi

                # ---- 6. Config + EULA ----------------------------------------------
                mkdir -p config
                if [ ! -f config/multiforge-server.toml ]; then
                    cp config/multiforge-server.toml.example config/multiforge-server.toml
                    say "wrote config/multiforge-server.toml (edit cores / threads-per-core)"
                else
                    say "config/multiforge-server.toml already present — left alone"
                fi
                if [ ! -f eula.txt ]; then
                    printf '# Accept the Minecraft EULA at https://aka.ms/MinecraftEULA\\n# by changing the line below to `eula=true`.\\neula=false\\n' > eula.txt
                    say "wrote eula.txt — set eula=true before first boot"
                fi

                say "done. Start the server with:  ./run.sh"
                say "roll back with:  mv run.sh.pre-multiforge-$STAMP.bak run.sh"
                """;
    }

    static String installBatScript() {
        return """
                @echo off
                REM MultiForge in-place converter.
                REM Run from inside an existing NeoForge 1.21.1 server directory.
                setlocal
                cd /d "%~dp0"

                if defined JAVA_HOME (
                    set "JAVA_BIN=%JAVA_HOME%\\bin\\java.exe"
                ) else (
                    set "JAVA_BIN=java"
                )

                for /f "tokens=3" %%v in ('"%JAVA_BIN%" -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%~v
                for /f "delims=. tokens=1" %%m in ("%JAVA_VER%") do set JAVA_MAJOR=%%m
                if not "%JAVA_MAJOR%"=="21" (
                    echo [multiforge] MultiForge requires JDK 21. Found: %JAVA_MAJOR% at %JAVA_BIN%
                    echo [multiforge] NeoForge 1.21.1 targets JDK 21; SpongeMixin packs crash on 22+
                    echo [multiforge] with "Unsupported class file major version 7X" at mod-scan.
                    echo [multiforge] Install Temurin 21 and set JAVA_HOME, then retry.
                    exit /b 1
                )

                if exist run.bat copy /y run.bat run.bat.pre-multiforge.bak >nul
                if exist run.sh copy /y run.sh run.sh.pre-multiforge.bak >nul
                if exist user_jvm_args.txt copy /y user_jvm_args.txt user_jvm_args.txt.pre-multiforge.bak >nul

                echo [multiforge] running the MultiForge installer
                "%JAVA_BIN%" -jar multiforge\\multiforge-installer.jar --installServer .
                if errorlevel 1 exit /b 1

                if not exist config mkdir config
                if not exist config\\multiforge-server.toml copy config\\multiforge-server.toml.example config\\multiforge-server.toml >nul
                if not exist eula.txt echo eula=false> eula.txt

                echo [multiforge] done. Start the server with:  run.bat
                echo [multiforge] NOTE: run.bat calls bare `java` — make sure JAVA_HOME points at JDK 21.
                endlocal
                """;
    }

    static String defaultConfig() {
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

    static String replacementReadme() {
        return """
                MultiForge — drop-in replacement bundle
                =======================================

                Converts an existing NeoForge 1.21.1 dedicated server directory to
                MultiForge in place. Your world, mods, and configs are left alone.

                What is in this archive:

                    multiforge/multiforge-installer.jar    the MultiForge installer
                    install-multiforge.sh                  Linux/macOS converter
                    install-multiforge.bat                 Windows converter
                    config/multiforge-server.toml.example  default config
                    README-MULTIFORGE.txt                  this file

                Steps:

                    1. Stop the running NeoForge server (/stop, wait for "Saving...").
                    2. Back up:  tar czf backup.tgz world/ mods/ config/ server.properties
                    3. Unzip this archive into the server directory.
                    4. Run:  ./install-multiforge.sh      (Windows: install-multiforge.bat)
                    5. Edit config/multiforge-server.toml — set cores / threads-per-core.
                    6. Start:  ./run.sh

                Step 4 needs internet the first time: the installer downloads the
                Minecraft server jar from Mojang and the NeoForge libraries, then
                applies MultiForge's patches locally. Nothing derived from Mojang's
                jar can be redistributed, which is why this archive carries an
                installer rather than a finished server.

                What it changes:

                    libraries/                        NeoForge + MultiForge + Minecraft
                    run.sh, run.bat, user_jvm_args.txt  regenerated (originals backed up
                                                        to *.pre-multiforge-<stamp>.bak)
                    config/multiforge-server.toml     written if absent
                    eula.txt                          written if absent (eula=false)

                Nothing under world/, mods/, or the rest of config/ is touched.

                Rolling back: restore the backed-up run.sh / run.bat / user_jvm_args.txt,
                and delete world/multiforge/, config/multiforge-server.toml, and
                logs/multiforge-*.log. The world is byte-compatible with upstream NeoForge.

                Requires JDK 21 exactly. Both the converter and the launcher it writes
                refuse to run on anything else — NeoForge 1.21.1 targets 21, and packs
                bundling SpongeMixin crash at mod-scan on JDK 22+ with "Unsupported
                class file major version 7X".

                Docs: https://github.com/0xnullsect0r/multiforge/tree/main/docs
                Support: https://github.com/0xnullsect0r/multiforge/issues
                """;
    }
}
