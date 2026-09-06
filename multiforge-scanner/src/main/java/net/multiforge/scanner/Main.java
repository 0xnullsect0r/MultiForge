/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.scanner;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.multiforge.scanner.rules.R01DirectChunkMapInvoke;
import net.multiforge.scanner.rules.R02OffThreadLevelSetBlock;
import net.multiforge.scanner.rules.R03BlockingFuture;
import net.multiforge.scanner.rules.R04UnsyncStaticMutation;
import net.multiforge.scanner.rules.R05EntitySetPosOffCoord;
import net.multiforge.scanner.rules.R06DirectServerChunkCacheMutation;
import net.multiforge.scanner.rules.R07RawDistanceManagerTicket;
import net.multiforge.scanner.rules.R08OffThreadBlockEntitySetChanged;
import net.multiforge.scanner.rules.R09SyncIoInTick;
import net.multiforge.scanner.rules.R10ThreadStartInModCtor;
import net.multiforge.scanner.rules.R11ReflectOnNeoforgedInternal;
import net.multiforge.scanner.rules.R12CaptureServerInLambda;

/**
 * CLI entry point. See {@code docs/design/scanner-rules.md} &sect;7.
 *
 * <pre>
 *   java -jar multiforge-scanner.jar [--sarif|--json] [--severity=warn|error]
 *       [--ignore-file &lt;path&gt;] &lt;jar-or-dir&gt;...
 * </pre>
 *
 * <p>All 12 rules (R01-R12) are wired in.
 */
public final class Main {

    private static final List<Rule> ACTIVE_RULES = List.of(
            new R01DirectChunkMapInvoke(),
            new R02OffThreadLevelSetBlock(),
            new R03BlockingFuture(),
            new R04UnsyncStaticMutation(),
            new R05EntitySetPosOffCoord(),
            new R06DirectServerChunkCacheMutation(),
            new R07RawDistanceManagerTicket(),
            new R08OffThreadBlockEntitySetChanged(),
            new R09SyncIoInTick(),
            new R10ThreadStartInModCtor(),
            new R11ReflectOnNeoforgedInternal(),
            new R12CaptureServerInLambda());

    /** {@code .multiforgeignore} filename, per doc §5.1. */
    private static final String IGNORE_FILE_NAME = ".multiforgeignore";

    private Main() {}

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err, ACTIVE_RULES);
        System.exit(exitCode);
    }

    /** Testable core: no {@code System.exit}, output streams injected. */
    static int run(String[] args, PrintStream out, PrintStream err, List<Rule> rules) {
        boolean sarif = false;
        Severity minSeverity = Severity.WARN;
        List<String> paths = new ArrayList<>();
        String explicitIgnoreFile = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--sarif".equals(a)) {
                sarif = true;
            } else if ("--json".equals(a)) {
                sarif = false;
            } else if (a.startsWith("--severity=")) {
                String v = a.substring("--severity=".length());
                if ("warn".equals(v)) {
                    minSeverity = Severity.WARN;
                } else if ("error".equals(v)) {
                    minSeverity = Severity.ERROR;
                } else {
                    err.println("Unknown --severity value: " + v + " (expected warn|error)");
                    usage(err);
                    return 2;
                }
            } else if ("--ignore-file".equals(a)) {
                if (i + 1 >= args.length) {
                    err.println("--ignore-file requires a path argument");
                    usage(err);
                    return 2;
                }
                explicitIgnoreFile = args[++i];
            } else if (a.startsWith("--")) {
                err.println("Unknown flag: " + a);
                usage(err);
                return 2;
            } else {
                paths.add(a);
            }
        }

        if (paths.isEmpty()) {
            err.println("No jar-or-dir inputs given.");
            usage(err);
            return 2;
        }

        RuleEngine engine = new RuleEngine(rules);
        List<Finding> allFindings = new ArrayList<>();
        List<Finding> reportedFindings = new ArrayList<>();
        List<Finding> staleSuppressions = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        int suppressedTotal = 0;

        // Doc §5.1: a global .multiforgeignore at the CLI's working directory applies across all
        // inputs; per-input files (sibling to the jar/dir, or an explicit --ignore-file override)
        // add to it, never replace it.
        IgnoreFile globalIgnore;
        try {
            globalIgnore = IgnoreFile.load(Path.of(IGNORE_FILE_NAME));
        } catch (java.io.IOException e) {
            err.println("Warning: failed to read " + IGNORE_FILE_NAME + " in the working directory: " + e.getMessage());
            globalIgnore = IgnoreFile.EMPTY;
        }
        if (explicitIgnoreFile != null) {
            try {
                globalIgnore = globalIgnore.merge(IgnoreFile.load(Path.of(explicitIgnoreFile)));
            } catch (java.io.IOException e) {
                err.println("Failed to read --ignore-file " + explicitIgnoreFile + ": " + e.getMessage());
                return 2;
            }
        }

        for (String p : paths) {
            File f = new File(p);
            if (!f.exists()) {
                err.println("Input does not exist: " + p);
                return 2;
            }
            inputs.add(p);

            IgnoreFile perInputIgnore;
            try {
                File parent = f.getAbsoluteFile().getParentFile();
                Path siblingIgnoreFile = parent == null ? null : parent.toPath().resolve(IGNORE_FILE_NAME);
                perInputIgnore = globalIgnore.merge(IgnoreFile.load(siblingIgnoreFile));
            } catch (java.io.IOException e) {
                err.println("Warning: failed to read " + IGNORE_FILE_NAME + " next to " + p + ": " + e.getMessage());
                perInputIgnore = globalIgnore;
            }

            try {
                RuleEngine.ScanOutcome outcome = engine.scan(f, perInputIgnore);
                allFindings.addAll(outcome.all());
                reportedFindings.addAll(outcome.reported());
                staleSuppressions.addAll(outcome.staleSuppressions());
                suppressedTotal += outcome.suppressedCount();
            } catch (java.io.IOException e) {
                err.println("Failed to scan " + p + ": " + e.getMessage());
                return 2;
            }
        }

        boolean anyError = reportedFindings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
        List<Finding> reported = minSeverity == Severity.ERROR
                ? reportedFindings.stream()
                        .filter(f -> f.severity() == Severity.ERROR)
                        .toList()
                : reportedFindings;

        if (sarif) {
            out.println(ReportEmitter.toSarif(rules, reported));
        } else {
            out.println(ReportEmitter.toJson(inputs, allFindings, reported, suppressedTotal, staleSuppressions));
        }

        return anyError ? 1 : 0;
    }

    private static void usage(PrintStream err) {
        err.println(
                """
                multiforge-scanner

                  java -jar multiforge-scanner.jar [--sarif|--json] [--severity=warn|error] \
                [--ignore-file <path>] <jar-or-dir>...

                    --json               Default output format (explicit no-op alias).
                    --sarif              Emit SARIF 2.1.0 instead of JSON.
                    --severity=warn      Report WARN and ERROR findings (default).
                    --severity=error     Report ERROR findings only (report-time filter; does not disable rules).
                    --ignore-file <path> Extra .multiforgeignore to merge in for every input (doc §5.1);
                                         a sibling .multiforgeignore next to each input and one in the
                                         working directory are always auto-discovered on top of this.

                  Exit codes: 0 = no unsuppressed ERROR finding, 1 = at least one unsuppressed ERROR
                  finding, 2 = usage/I-O error.
                """
                        .stripIndent());
    }
}
