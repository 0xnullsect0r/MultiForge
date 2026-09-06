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
package net.multiforge.scanner.rules;

import java.util.function.Consumer;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Rule;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Shared scaffolding for rules that need ASM's tree API to inspect a
 * method's full instruction list (for fingerprint window lookahead, or
 * multi-instruction pattern matching). All of R01-R06 use this base — see
 * the "key design decisions" note in the Track C2.1-7 plan: this trades the
 * doc's streaming-vs-tree split for uniform, simpler rule code plus correct
 * &plusmn;2 fingerprint windows everywhere.
 */
public abstract class AbstractTreeRule implements Rule {

    @Override
    public final void visit(ClassContext ctx, ClassReader reader, Consumer<Finding> emit) {
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        try {
            reader.accept(cn, ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            // Never crash the scanner on an unparseable class (doc §2 / CLAUDE.md rule 5 spirit).
            return;
        }
        scanClass(ctx, cn, emit);
    }

    protected abstract void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit);
}
