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
package net.multiforge.runtime.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkLoadLevel;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.config.MultiForgeConfigStore;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.pin.RegionPin;
import net.multiforge.runtime.region.pin.RegionPinManager;

/**
 * Pure-Java argument parser + dispatcher for the /multiforge tree.
 * The M5 patch wires it to NeoForge's Brigadier command registration;
 * the dispatcher itself is dependency-free so its parsing is unit
 * testable.
 *
 * <p>Supported grammar:
 *
 * <pre>
 *   /multiforge config cores &lt;n&gt;
 *   /multiforge config threads &lt;n&gt;
 *   /multiforge region size &lt;chunks&gt;
 *   /multiforge region mode player-only|full-world
 *   /multiforge region pin &lt;id&gt; &lt;world&gt; &lt;fromCX&gt; &lt;fromCZ&gt; &lt;toCX&gt; &lt;toCZ&gt;
 *   /multiforge region unpin &lt;id&gt;
 *   /multiforge region list
 *   /multiforge probes           — dump all ProbeRegistry counters (diagnostics)
 *   /multiforge probes &lt;prefix&gt;  — dump counters whose key starts with prefix
 *   /multiforge chunks &lt;world&gt;   — summarize the M9-bridge chunk shadow
 *                                  (counts by ChunkLoadLevel; requires the fork
 *                                  ChunkMap bridge to be installed)
 *   /multiforge warn list         — show recent violations from ViolationLogger
 *   /multiforge warn clear        — reset the violation history ring buffer
 *   /multiforge certify &lt;modId&gt;  — run the scanner against a jar in ./mods,
 *                                  print pass/fail per rule (see docs/certification.md)
 *   /multiforge certify all       — same, for every jar under ./mods
 * </pre>
 */
public final class MultiForgeCommandDispatcher {

    /** R01..R12 per {@code docs/design/scanner-rules.md} §4 — fixed, frozen rule set. */
    private static final List<String> SCANNER_RULE_IDS =
            List.of("R01", "R02", "R03", "R04", "R05", "R06", "R07", "R08", "R09", "R10", "R11", "R12");

    /**
     * Matches one {@code "ruleId": "R0N", ..., "severity": "WARN|ERROR"}
     * finding entry in the scanner's canonical JSON report (§6.1 of
     * scanner-rules.md fixes ruleId-before-severity field order within a
     * finding object). Deliberately hand-rolled rather than pulling in a
     * JSON library: {@code multiforge-runtime} has none on its classpath
     * today and the report shape here is small, self-produced, and
     * non-adversarial.
     */
    private static final Pattern FINDING_PATTERN = Pattern.compile(
            "\"ruleId\"\\s*:\\s*\"(R\\d{2})\"[^{}]*?\"severity\"\\s*:\\s*\"(WARN|ERROR)\"", Pattern.DOTALL);

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private final MultiForgeConfigStore configStore;
    private final RegionPinManager pins;
    private final Function<WorldRef, ChunkHolderManager> chunkManagers;
    private final Path modsDir;
    private final ScannerRunner scannerRunner;

    public MultiForgeCommandDispatcher(MultiForgeConfigStore configStore, RegionPinManager pins) {
        this(configStore, pins, null);
    }

    /**
     * Full constructor for the production fork wiring: {@code
     * chunkManagers} looks up a world's {@link ChunkHolderManager} for
     * the {@code /multiforge chunks} command. Pass {@code null} if the
     * chunk-system bridge (M9 sub-step 1) is not yet installed; the
     * chunks subcommand then reports "not installed" instead of NPE'ing.
     *
     * <p>{@code /multiforge certify} uses the default mods directory
     * ({@code ./mods}) and the default {@link ScannerRunner} (shells out
     * to {@code java -jar <multiforge.scanner.jar sysprop, default
     * "multiforge-scanner.jar">}). Use the 5-arg constructor to override
     * either for tests or non-default deployments.
     */
    public MultiForgeCommandDispatcher(
            MultiForgeConfigStore configStore,
            RegionPinManager pins,
            Function<WorldRef, ChunkHolderManager> chunkManagers) {
        this(configStore, pins, chunkManagers, Path.of("mods"), defaultScannerRunner());
    }

    /**
     * Full constructor with the {@code /multiforge certify} seams
     * exposed: {@code modsDir} is where {@code certify &lt;modId|all&gt;}
     * looks for jars, and {@code scannerRunner} is how a jar gets handed
     * to the scanner (a {@link ProcessBuilder}-backed shell-out by
     * default; tests inject a stub instead of invoking a real process).
     */
    public MultiForgeCommandDispatcher(
            MultiForgeConfigStore configStore,
            RegionPinManager pins,
            Function<WorldRef, ChunkHolderManager> chunkManagers,
            Path modsDir,
            ScannerRunner scannerRunner) {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.chunkManagers = chunkManagers;
        this.modsDir = Objects.requireNonNull(modsDir, "modsDir");
        this.scannerRunner = Objects.requireNonNull(scannerRunner, "scannerRunner");
    }

    /**
     * Handle a parsed command. {@code output} receives one or more
     * lines of feedback; the caller sends those to the operator's
     * chat / console.
     *
     * @return true if the command succeeded, false on a usage error
     *         (usage lines are written to output).
     */
    public boolean dispatch(String[] args, Consumer<String> output) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(output, "output");
        if (args.length == 0) {
            output.accept("Usage: /multiforge <config|region|probes|chunks|warn|certify> ...");
            return false;
        }
        return switch (args[0]) {
            case "config" -> handleConfig(args, output);
            case "region" -> handleRegion(args, output);
            case "probes" -> handleProbes(args, output);
            case "chunks" -> handleChunks(args, output);
            case "warn" -> handleWarn(args, output);
            case "certify" -> handleCertify(args, output);
            default -> {
                output.accept("Unknown subcommand: " + args[0]);
                yield false;
            }
        };
    }

    /**
     * Summarize the M9-bridge chunk shadow for one world: counts by
     * {@link ChunkLoadLevel}. Requires the fork bridge
     * ({@code net.multiforge.neoforge.ChunkHolderManagerBridge}) to be
     * installed so that Vanilla ticket-level updates propagate into
     * {@link ChunkHolderManager}.
     *
     * <p>Usage: {@code /multiforge chunks <world>} where {@code <world>}
     * is a namespaced dimension id like {@code minecraft:overworld}.
     */
    private boolean handleChunks(String[] args, Consumer<String> output) {
        if (chunkManagers == null) {
            output.accept("Chunk-system bridge not installed. Ensure ChunkHolderManagerBridge is wired.");
            return false;
        }
        if (args.length < 2) {
            output.accept("Usage: /multiforge chunks <world>");
            return false;
        }
        WorldRef world = WorldRef.of(args[1]);
        ChunkHolderManager manager = chunkManagers.apply(world);
        if (manager == null) {
            output.accept("No chunk manager for world '" + args[1] + "' (never touched by the bridge).");
            return true;
        }
        java.util.EnumMap<ChunkLoadLevel, Integer> counts = new java.util.EnumMap<>(ChunkLoadLevel.class);
        for (ChunkLoadLevel l : ChunkLoadLevel.values()) counts.put(l, 0);
        for (NewChunkHolder h : manager.holders()) {
            counts.merge(h.level(), 1, Integer::sum);
        }
        int total = manager.holderCount();
        output.accept("world=" + args[1] + " holders=" + total);
        for (ChunkLoadLevel l : ChunkLoadLevel.values()) {
            output.accept("  " + l.name() + " (distance=" + l.distance() + "): " + counts.get(l));
        }
        return true;
    }

    /**
     * Dump {@link ProbeRegistry} counters — the diagnostic surface the
     * ownership guards, watchdog, and future M8 subsystems bump into
     * on race detection. Operators use this to answer "are we hitting
     * region-tick.overrun?" or "is Level.setBlock:off-thread growing?"
     * without needing to grep the server log.
     *
     * <p>{@code /multiforge probes} dumps every counter; {@code /multiforge
     * probes &lt;prefix&gt;} filters to keys starting with the given prefix
     * (e.g. {@code /multiforge probes region-tick} for just the watchdog
     * counters). Snapshot is sorted for stable operator-readable output.
     */
    private boolean handleProbes(String[] args, Consumer<String> output) {
        String prefix = args.length > 1 ? args[1] : "";
        Map<String, Long> snap = ProbeRegistry.snapshot();
        int matched = 0;
        for (Map.Entry<String, Long> e : snap.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            output.accept(e.getKey() + " = " + e.getValue());
            matched++;
        }
        if (matched == 0) {
            output.accept(prefix.isEmpty() ? "(no probes recorded)" : "(no probes matching prefix '" + prefix + "')");
        }
        return true;
    }

    private boolean handleConfig(String[] args, Consumer<String> output) {
        if (args.length < 3) {
            output.accept("Usage: /multiforge config <cores|threads> <n>");
            return false;
        }
        int n;
        try {
            n = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            output.accept("Not a number: " + args[2]);
            return false;
        }
        if (n < 1 || n > 4096) {
            output.accept("Value out of range: " + n);
            return false;
        }
        try {
            MultiForgeConfig next =
                    switch (args[1]) {
                        case "cores" -> configStore.update(c -> c.withCores(n));
                        case "threads" -> configStore.update(c -> c.withThreadsPerCore(n));
                        default -> {
                            output.accept("Unknown config key: " + args[1]);
                            yield null;
                        }
                    };
            if (next == null) return false;
            output.accept("Set " + args[1] + " = " + n + " (worker pool now " + next.tickWorkerCount() + " threads)");
            return true;
        } catch (IOException e) {
            output.accept("Failed to persist config: " + e.getMessage());
            return false;
        }
    }

    private boolean handleRegion(String[] args, Consumer<String> output) {
        if (args.length < 2) {
            output.accept("Usage: /multiforge region <size|mode|pin|unpin|list> ...");
            return false;
        }
        return switch (args[1]) {
            case "size" -> handleRegionSize(args, output);
            case "mode" -> handleRegionMode(args, output);
            case "pin" -> handlePin(args, output);
            case "unpin" -> handleUnpin(args, output);
            case "list" -> handleList(output);
            default -> {
                output.accept("Unknown region subcommand: " + args[1]);
                yield false;
            }
        };
    }

    private boolean handleRegionSize(String[] args, Consumer<String> output) {
        if (args.length < 3) {
            output.accept("Usage: /multiforge region size <chunks> (must be a power of 2 from 1..256)");
            return false;
        }
        int chunks;
        try {
            chunks = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            output.accept("Not a number: " + args[2]);
            return false;
        }
        if (chunks < 1 || chunks > 256 || Integer.bitCount(chunks) != 1) {
            output.accept("Size must be a power of 2 between 1 and 256");
            return false;
        }
        int shift = Integer.numberOfTrailingZeros(chunks);
        try {
            configStore.update(c -> c.withRegionSize(shift));
            output.accept("Region size set to " + chunks + " chunks per side (shift=" + shift + ")");
            return true;
        } catch (IOException e) {
            output.accept("Failed to persist: " + e.getMessage());
            return false;
        }
    }

    private boolean handleRegionMode(String[] args, Consumer<String> output) {
        if (args.length < 3) {
            output.accept("Usage: /multiforge region mode player-only|full-world");
            return false;
        }
        MultiForgeConfig.RegionMode mode;
        try {
            mode = MultiForgeConfig.RegionMode.valueOf(args[2].replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            output.accept("Unknown mode: " + args[2] + " (accepted: player-only, full-world)");
            return false;
        }
        try {
            configStore.update(c -> c.withRegionMode(mode));
            output.accept("Region mode set to " + args[2]);
            return true;
        } catch (IOException e) {
            output.accept("Failed to persist: " + e.getMessage());
            return false;
        }
    }

    private boolean handlePin(String[] args, Consumer<String> output) {
        if (args.length < 8) {
            output.accept("Usage: /multiforge region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>");
            return false;
        }
        String id = args[2];
        WorldRef world = WorldRef.of(args[3]);
        int fx, fz, tx, tz;
        try {
            fx = Integer.parseInt(args[4]);
            fz = Integer.parseInt(args[5]);
            tx = Integer.parseInt(args[6]);
            tz = Integer.parseInt(args[7]);
        } catch (NumberFormatException e) {
            output.accept("Chunk coordinates must be integers");
            return false;
        }
        try {
            RegionPin pin = pins.add(new RegionPin(id, world, fx, fz, tx, tz));
            pins.save();
            output.accept("Pinned " + pin.chunkCount() + " chunks as '" + pin.id() + "' in " + world.dimensionId());
            return true;
        } catch (IllegalStateException e) {
            output.accept(e.getMessage());
            return false;
        } catch (IOException e) {
            output.accept("Failed to persist pin: " + e.getMessage());
            return false;
        }
    }

    private boolean handleUnpin(String[] args, Consumer<String> output) {
        if (args.length < 3) {
            output.accept("Usage: /multiforge region unpin <id>");
            return false;
        }
        RegionPin removed = pins.remove(args[2]);
        if (removed == null) {
            output.accept("No such pin: " + args[2]);
            return false;
        }
        try {
            pins.save();
        } catch (IOException e) {
            output.accept("Removed from memory but failed to persist: " + e.getMessage());
            return false;
        }
        output.accept("Removed pin '" + removed.id() + "'");
        return true;
    }

    private boolean handleList(Consumer<String> output) {
        List<RegionPin> all = List.copyOf(pins.all());
        if (all.isEmpty()) {
            output.accept("No pinned regions.");
            return true;
        }
        output.accept("Pinned regions (" + all.size() + "):");
        for (RegionPin p : all) {
            output.accept("  " + p.id() + " " + p.world().dimensionId() + " ["
                    + p.fromChunkX() + "," + p.fromChunkZ() + "]..["
                    + p.toChunkX() + "," + p.toChunkZ() + "] (" + p.chunkCount() + " chunks)");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // /multiforge warn — operator-facing view over ViolationLogger's
    // bounded recent-violation history. See docs/debugging-violations.md.
    // ------------------------------------------------------------------

    /**
     * Dispatches {@code /multiforge warn list} and {@code /multiforge
     * warn clear}. Both read/mutate {@link ViolationLogger}'s static
     * recent-violation ring buffer — there is no per-dispatcher state
     * here, matching {@link #handleProbes} against {@link ProbeRegistry}.
     */
    private boolean handleWarn(String[] args, Consumer<String> output) {
        if (args.length < 2) {
            output.accept("Usage: /multiforge warn <list|clear>");
            return false;
        }
        return switch (args[1]) {
            case "list" -> handleWarnList(output);
            case "clear" -> handleWarnClear(output);
            default -> {
                output.accept("Unknown warn subcommand: " + args[1]);
                yield false;
            }
        };
    }

    private boolean handleWarnList(Consumer<String> output) {
        List<ViolationLogger.ViolationEvent> recent = ViolationLogger.recent();
        if (recent.isEmpty()) {
            output.accept("(no recent violations)");
            return true;
        }
        output.accept("Recent violations (" + recent.size() + ", oldest first):");
        for (ViolationLogger.ViolationEvent e : recent) {
            String mod = e.modId() == null ? "-" : e.modId();
            output.accept("  " + TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(e.epochMillis())) + " [" + mod + "] "
                    + e.site() + ": " + e.detail());
        }
        return true;
    }

    private boolean handleWarnClear(Consumer<String> output) {
        int cleared = ViolationLogger.clearRecent();
        output.accept("Cleared " + cleared + " recent violation record(s). Rate-limit budgets are untouched.");
        return true;
    }

    // ------------------------------------------------------------------
    // /multiforge certify — thin wrapper over the multiforge-scanner CLI
    // (docs/design/scanner-rules.md §7). Never reimplements rule logic;
    // only resolves jars under `modsDir`, shells out per jar, and
    // reformats the JSON report as a pass/fail-per-rule operator view.
    // See docs/certification.md.
    // ------------------------------------------------------------------

    /**
     * Runs the scanner against one mod jar. Injectable so tests don't
     * need to fork a real {@code java -jar multiforge-scanner.jar}
     * process; the production default ({@link
     * #defaultScannerRunner()}) does exactly that.
     */
    @FunctionalInterface
    public interface ScannerRunner {
        ScanResult run(Path modJar) throws IOException, InterruptedException;
    }

    /**
     * Result of one scanner invocation. {@code exitCode} follows
     * scanner-rules.md §7: {@code 0} no ERROR findings, {@code 1} at
     * least one unsuppressed ERROR finding, {@code 2} scanner internal
     * failure (bad jar, I/O error, or — here — jar-not-found).
     */
    public record ScanResult(int exitCode, String stdout, String stderr) {}

    private static ScannerRunner defaultScannerRunner() {
        Path scannerJar = Path.of(System.getProperty("multiforge.scanner.jar", "multiforge-scanner.jar"));
        return modJar -> runScannerProcess(scannerJar, modJar);
    }

    private static ScanResult runScannerProcess(Path scannerJar, Path modJar) throws IOException, InterruptedException {
        if (!Files.isReadable(scannerJar)) {
            return new ScanResult(2, "", "scanner jar not found or unreadable: " + scannerJar);
        }
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", scannerJar.toString(), "--json", "--severity=warn", modJar.toString());
        Process proc = pb.start();
        String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = proc.waitFor();
        return new ScanResult(exit, stdout, stderr);
    }

    private boolean handleCertify(String[] args, Consumer<String> output) {
        if (args.length < 2) {
            output.accept("Usage: /multiforge certify <modId|all>");
            return false;
        }
        List<Path> jars;
        try {
            jars = "all".equals(args[1]) ? listModJars(null) : listModJars(args[1]);
        } catch (IOException e) {
            output.accept("Failed to read mods directory " + modsDir + ": " + e.getMessage());
            return false;
        }
        if (jars.isEmpty()) {
            output.accept(
                    "all".equals(args[1])
                            ? "No mod jars found under " + modsDir
                            : "No jar matching mod id '" + args[1] + "' found under " + modsDir);
            return false;
        }
        boolean allCertified = true;
        for (Path jar : jars) {
            allCertified &= certifyOne(jar, output);
        }
        return allCertified;
    }

    /**
     * Lists {@code *.jar} files directly under {@code modsDir}. {@code
     * modIdPrefix == null} lists every jar (for {@code certify all});
     * otherwise filters to filenames starting with the given id
     * (case-insensitive) — the dispatcher has no mod-id-to-filename
     * registry to consult, so this is a best-effort glob, same as
     * mod-jar discovery elsewhere in the ecosystem.
     */
    private List<Path> listModJars(String modIdPrefix) throws IOException {
        if (!Files.isDirectory(modsDir)) {
            return List.of();
        }
        try (var stream = Files.list(modsDir)) {
            return stream.filter(p ->
                            p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(p -> modIdPrefix == null
                            || p.getFileName()
                                    .toString()
                                    .toLowerCase(Locale.ROOT)
                                    .startsWith(modIdPrefix.toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
        }
    }

    private boolean certifyOne(Path jar, Consumer<String> output) {
        output.accept("=== " + jar.getFileName() + " ===");
        ScanResult result;
        try {
            result = scannerRunner.run(jar);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output.accept("  scanner run interrupted: " + e.getMessage());
            output.accept("NOT CERTIFIED: " + jar.getFileName());
            return false;
        } catch (IOException e) {
            output.accept("  scanner failed to run: " + e.getMessage());
            output.accept("NOT CERTIFIED: " + jar.getFileName());
            return false;
        }
        if (result.exitCode() == 2) {
            output.accept("  scanner internal failure: " + result.stderr().trim());
            output.accept("NOT CERTIFIED: " + jar.getFileName());
            return false;
        }
        Map<String, String> worstByRule = worstSeverityByRule(result.stdout());
        boolean hasError = worstByRule.containsValue("ERROR");
        for (String rule : SCANNER_RULE_IDS) {
            String severity = worstByRule.get(rule);
            output.accept("  " + rule + ": " + (severity == null ? "PASS" : "FAIL (" + severity + ")"));
        }
        boolean certified = !hasError;
        output.accept(certified ? "CERTIFIED: " + jar.getFileName() : "NOT CERTIFIED: " + jar.getFileName());
        return certified;
    }

    /**
     * Parses the scanner's JSON {@code findings[]} array (per
     * scanner-rules.md §6.1) down to "worst severity seen per rule ID" —
     * ERROR beats WARN if a rule fired more than once at different
     * severities (it never should, since severity is fixed per-rule per
     * §2.2, but the fold is defensive rather than assuming that holds).
     */
    private static Map<String, String> worstSeverityByRule(String scannerJson) {
        Map<String, String> worst = new HashMap<>();
        Matcher m = FINDING_PATTERN.matcher(scannerJson);
        while (m.find()) {
            String ruleId = m.group(1);
            String severity = m.group(2);
            worst.merge(ruleId, severity, (a, b) -> "ERROR".equals(a) || "ERROR".equals(b) ? "ERROR" : b);
        }
        return worst;
    }
}
