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
 * @param typeHierarchy the current scan's {@link TypeHierarchy} index — lets a rule resolve
 *     whether an {@code INVOKEVIRTUAL}/{@code INVOKEINTERFACE} owner is a subtype of some target
 *     type even when the owner is a mod-defined subclass (round-6 fork C HIGH finding, R03).
 */
public record ClassContext(
        String className,
        String superName,
        List<String> interfaces,
        Set<String> classAnnotations,
        String sourceJarName,
        boolean isModEntryClass,
        TypeHierarchy typeHierarchy) {

    /** Internal descriptor of NeoForge's {@code @Mod} annotation, per scanner-rules.md §1.3. */
    public static final String MOD_ANNOTATION_DESC = "Lnet/neoforged/fml/common/Mod;";

    /**
     * Convenience constructor for callers (mainly tests) that don't have a jar-wide {@link
     * TypeHierarchy} to hand — defaults to {@link TypeHierarchy#EMPTY}, which still resolves
     * pure-JDK hierarchies via classpath reflection, just not mod-defined subtypes.
     */
    public ClassContext(
            String className,
            String superName,
            List<String> interfaces,
            Set<String> classAnnotations,
            String sourceJarName,
            boolean isModEntryClass) {
        this(className, superName, interfaces, classAnnotations, sourceJarName, isModEntryClass, TypeHierarchy.EMPTY);
    }
}
