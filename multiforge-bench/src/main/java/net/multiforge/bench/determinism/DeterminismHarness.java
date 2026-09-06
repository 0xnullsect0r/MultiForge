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
package net.multiforge.bench.determinism;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * CLI entry point for the M7-scoped determinism regression: given two
 * world save directories that were produced by two servers run with the
 * same fixed seed and tick count — one baseline (upstream NeoForge patch
 * set only) and one patched (with {@code multiforge-patches/01-ownership/}
 * applied) — verify they are byte-identical.
 *
 * <p><b>M7 scope note</b> (see docs/blueprint.md M7): the 01-ownership
 * patch group only adds pass-through guards at mutation sites; it does
 * not add any parallelism, does not reorder mutations, and consumes no
 * RNG. Under those constraints, byte-identical world saves are a
 * legitimate and cheap correctness check. This harness will need to be
 * replaced with a canonicalized/semantic NBT diff starting at M8, once
 * real parallel region ticking makes byte-identical output impossible
 * even for correct code (chunk save order becomes legitimately
 * non-deterministic).
 *
 * <p>This class is deliberately framework-independent: it does not launch
 * the servers itself, does not depend on Gradle or NeoGradle at build or
 * run time, and does not know anything about Minecraft's NBT format. It
 * hashes files and reports the first differing one. Server launch is
 * left as a separate manual/CI step so the harness can be reused
 * unchanged across MC versions.
 */
public final class DeterminismHarness {

    /**
     * Two positional world-dir arguments, plus optional {@code --mode=}
     * and {@code --seed=} flags in any order after them. Positional args
     * must come first — this keeps the pre-Phase-7 two-arg invocation
     * ({@code DeterminismHarness <baseline> <patched>}) working unchanged
     * with the default {@link WorldDiff.DiffMode#BYTE_IDENTICAL}.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            printUsageAndExit();
            return; // unreachable — printUsageAndExit exits — but keeps args non-null to the compiler
        }

        Path baseline = Path.of(args[0]);
        Path patched = Path.of(args[1]);

        WorldDiff.DiffMode mode = WorldDiff.DiffMode.BYTE_IDENTICAL;
        String seed = null;

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--mode=")) {
                String raw = arg.substring("--mode=".length());
                try {
                    mode = WorldDiff.DiffMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    System.err.println("invalid --mode value: " + raw + " (expected BYTE_IDENTICAL or SEMANTIC)");
                    System.exit(2);
                }
            } else if (arg.startsWith("--seed=")) {
                // Not consumed by the diff itself — WorldDiff.compare has no seed
                // parameter, since determinism is verified by comparing two
                // already-captured world directories, not by re-running the
                // server. Printed back below purely for provenance in the run
                // log (so a CI artifact or verification-doc entry can be
                // traced back to the seed that produced the two captures).
                seed = arg.substring("--seed=".length());
            } else {
                System.err.println("unrecognized argument: " + arg);
                printUsageAndExit();
                return;
            }
        }

        for (Path p : List.of(baseline, patched)) {
            if (!Files.isDirectory(p)) {
                System.err.println("not a directory: " + p);
                System.exit(2);
            }
        }

        System.out.println("DeterminismHarness: mode=" + mode + (seed != null ? ", seed=" + seed : ""));

        WorldDiff.Result result = WorldDiff.compare(baseline, patched, mode);
        System.out.println(result.summary());
        if (!result.matches()) {
            System.exit(1);
        }
    }

    private static void printUsageAndExit() {
        System.err.println(
                "usage: DeterminismHarness <baseline-world-dir> <patched-world-dir> [--mode=BYTE_IDENTICAL|SEMANTIC] [--seed=<n>]");
        System.err.println(
                "  each positional argument must point to a saved world directory (contains region/, level.dat, etc.)");
        System.err.println("  --mode defaults to BYTE_IDENTICAL when omitted");
        System.err.println("  --seed is provenance-only — printed back in the log, not used by the diff itself");
        System.exit(2);
    }

    private DeterminismHarness() {}
}
