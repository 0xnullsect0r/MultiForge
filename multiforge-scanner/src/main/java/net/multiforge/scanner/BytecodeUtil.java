/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

/** Small shared bytecode-inspection helpers used by multiple rules. */
public final class BytecodeUtil {

    private BytecodeUtil() {}

    /**
     * Best-effort source line covering {@code target}, per {@code Finding.line}'s contract:
     * {@code -1} if no {@link LineNumberNode} entry covers the site (stripped debug info, or the
     * target precedes the first line-number marker in the method).
     */
    public static int lineOf(MethodNode mn, AbstractInsnNode target) {
        int line = -1;
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof LineNumberNode ln) {
                line = ln.line;
            }
            if (insn == target) {
                return line;
            }
        }
        return -1;
    }

    /** {@code "name(descriptor)"} finding method-key per doc §5.2. */
    public static String methodKey(MethodNode mn) {
        return mn.name + mn.desc;
    }

    /** {@code "<field:name>"} finding method-key for field-only findings (currently only R04). */
    public static String fieldKey(String fieldName) {
        return "<field:" + fieldName + ">";
    }
}
