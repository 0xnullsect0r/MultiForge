/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

import java.util.List;
import java.util.Set;

/**
 * Metadata about the class currently being scanned, computed once by {@link
 * RuleEngine} before rule fan-out. Used by rules for filtering. See {@code
 * docs/design/scanner-rules.md} &sect;1.3.
 *
 * @param className internal (slash-separated) name, e.g. {@code "com/example/mod/FooBlock"}
 * @param superName internal name of the superclass, or {@code null} for {@code java/lang/Object}
 *     edge cases the ASM reader still reports
 * @param interfaces internal names of directly-implemented interfaces
 * @param classAnnotations internal descriptors of class-level annotations, e.g. {@code
 *     "Lnet/neoforged/fml/common/Mod;"}
 * @param sourceJarName the jar (or directory root) this class was read from, for report grouping
 *     / {@code .multiforgeignore} scoping
 * @param isModEntryClass true iff {@code @Mod} is present — cached so rules don't re-scan
 */
public record ClassContext(
        String className,
        String superName,
        List<String> interfaces,
        Set<String> classAnnotations,
        String sourceJarName,
        boolean isModEntryClass) {

    /** Internal descriptor of NeoForge's {@code @Mod} annotation, per scanner-rules.md §1.3. */
    public static final String MOD_ANNOTATION_DESC = "Lnet/neoforged/fml/common/Mod;";
}
