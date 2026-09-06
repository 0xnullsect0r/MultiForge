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
package net.multiforge.scanner;

import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;

/**
 * A single, stateless detection rule ("R01".."R12" per {@code
 * docs/design/scanner-rules.md} &sect;4).
 *
 * <p>Note: this interface is intentionally simpler than the {@code
 * ClassVisitor forClass(ClassContext, FindingSink)} shape sketched in doc
 * &sect;2 — that shape exists to fan every rule out from a single {@code
 * ClassReader.accept} pass for speed on large mod jars. This track lands the
 * scaffold and the first six rules without that optimization: each rule may
 * call {@code reader.accept(...)} independently. Revisit if/when scan-time
 * profiling on a real modpack corpus (Track X.8) shows it matters.
 */
public interface Rule {

    /** "R01".."R12". */
    String id();

    /** One-word-ish kebab name, e.g. {@code "direct-ChunkMap-invoke"} (doc §2's {@code Rule.name()}). */
    String name();

    /** One-sentence description for report metadata (SARIF {@code driver.rules[].shortDescription}). */
    String description();

    /** Fixed severity for every finding this rule produces (doc §2.2). */
    Severity severity();

    /**
     * Called once per class visited. Implementations must never throw for a
     * pattern they don't understand — an unparseable method body is skipped
     * silently rather than crashing the scan (doc §2, mirrors CLAUDE.md rule
     * 5's "reroute + warn, never refuse" spirit).
     *
     * @param ctx metadata about the class being scanned
     * @param reader a reusable {@link ClassReader} over the class's bytecode
     * @param emit sink for zero or more findings this rule detects
     */
    void visit(ClassContext ctx, ClassReader reader, Consumer<Finding> emit);
}
