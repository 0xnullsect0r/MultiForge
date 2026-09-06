/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import net.multiforge.scanner.rules.R01DirectChunkMapInvoke;
import net.multiforge.scanner.rules.R02OffThreadLevelSetBlock;
import net.multiforge.scanner.rules.R03BlockingFuture;
import net.multiforge.scanner.rules.R04UnsyncStaticMutation;
import net.multiforge.scanner.rules.R05EntitySetPosOffCoord;
import net.multiforge.scanner.rules.R06DirectServerChunkCacheMutation;

/**
 * CLI entry point. See {@code docs/design/scanner-rules.md} &sect;7.
 *
 * <pre>
 *   java -jar multiforge-scanner.jar [--sarif|--json] [--severity=warn|error] &lt;jar-or-dir&gt;...
 * </pre>
 *
 * <p>Only R01-R06 are wired in as of this track; R07-R12 land in a parallel commit and get added
 * to {@link #ACTIVE_RULES} then.
 */
public final class Main {

    private static final List<Rule> ACTIVE_RULES = List.of(
            new R01DirectChunkMapInvoke(),
            new R02OffThreadLevelSetBlock(),
            new R03BlockingFuture(),
            new R04UnsyncStaticMutation(),
            new R05EntitySetPosOffCoord(),
            new R06DirectServerChunkCacheMutation());

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

        for (String a : args) {
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
        List<String> inputs = new ArrayList<>();
        for (String p : paths) {
            File f = new File(p);
            if (!f.exists()) {
                err.println("Input does not exist: " + p);
                return 2;
            }
            inputs.add(p);
            try {
                allFindings.addAll(engine.scan(f));
            } catch (java.io.IOException e) {
                err.println("Failed to scan " + p + ": " + e.getMessage());
                return 2;
            }
        }

        boolean anyError = allFindings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
        List<Finding> reported = minSeverity == Severity.ERROR
                ? allFindings.stream()
                        .filter(f -> f.severity() == Severity.ERROR)
                        .toList()
                : allFindings;

        if (sarif) {
            out.println(ReportEmitter.toSarif(reported));
        } else {
            out.println(ReportEmitter.toJson(inputs, allFindings, reported));
        }

        return anyError ? 1 : 0;
    }

    private static void usage(PrintStream err) {
        err.println(
                """
                multiforge-scanner

                  java -jar multiforge-scanner.jar [--sarif|--json] [--severity=warn|error] <jar-or-dir>...

                    --json               Default output format (explicit no-op alias).
                    --sarif              Emit SARIF 2.1.0 instead of JSON.
                    --severity=warn      Report WARN and ERROR findings (default).
                    --severity=error     Report ERROR findings only (report-time filter; does not disable rules).

                  Exit codes: 0 = no ERROR findings, 1 = at least one ERROR finding, 2 = usage/I-O error.
                """
                        .stripIndent());
    }
}
