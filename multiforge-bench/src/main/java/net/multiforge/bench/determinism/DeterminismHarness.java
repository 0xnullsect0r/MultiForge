/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.bench.determinism;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: DeterminismHarness <baseline-world-dir> <patched-world-dir>");
            System.err.println(
                    "  each argument must point to a saved world directory (contains region/, level.dat, etc.)");
            System.exit(2);
        }

        Path baseline = Path.of(args[0]);
        Path patched = Path.of(args[1]);

        for (Path p : List.of(baseline, patched)) {
            if (!Files.isDirectory(p)) {
                System.err.println("not a directory: " + p);
                System.exit(2);
            }
        }

        WorldDiff.Result result = WorldDiff.compare(baseline, patched);
        System.out.println(result.summary());
        if (!result.matches()) {
            System.exit(1);
        }
    }

    private DeterminismHarness() {}
}
