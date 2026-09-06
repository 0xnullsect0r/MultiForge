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
package net.multiforge.scanner.rules;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import net.multiforge.scanner.TickReachability;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R04 — {@code unsync-static-mutation}. See {@code docs/design/scanner-rules.md} &sect;4 (R04).
 *
 * <p>WARN: a {@code PUTSTATIC} to a non-final static field declared in a {@code @Mod}-annotated
 * class, from a method the tick-reachability heuristic classifies as reachable on the
 * region-tick thread.
 *
 * <p>Per doc §5.2, this rule's finding {@code methodKey} is the {@code <field:name>} token, not
 * the enclosing tick-handler method — reconciled at {@code visitEnd}-equivalent time (i.e. after
 * both {@code cn.fields} and {@code cn.methods} are fully populated by the tree API), so field
 * declaration order relative to methods never matters.
 */
public final class R04UnsyncStaticMutation extends AbstractTreeRule {

    @Override
    public String id() {
        return "R04";
    }

    @Override
    public String name() {
        return "unsync-static-mutation";
    }

    @Override
    public String description() {
        return "Non-final static field of a @Mod class written from tick-reachable code.";
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        if (!ctx.isModEntryClass()) {
            return;
        }
        String classFqn = ctx.className().replace('/', '.');

        Map<String, Boolean> fieldIsFinal = new HashMap<>();
        for (FieldNode fn : cn.fields) {
            fieldIsFinal.put(fn.name, (fn.access & Opcodes.ACC_FINAL) != 0);
        }

        var tickReachable = TickReachability.compute(cn);
        for (MethodNode mn : cn.methods) {
            if (!tickReachable.contains(mn.name + mn.desc)) {
                continue;
            }
            for (var insn : mn.instructions) {
                if (!(insn instanceof FieldInsnNode fi)) {
                    continue;
                }
                if (fi.getOpcode() != Opcodes.PUTSTATIC || !fi.owner.equals(cn.name)) {
                    continue;
                }
                Boolean isFinal = fieldIsFinal.get(fi.name);
                if (isFinal == null || isFinal) {
                    continue;
                }
                String methodKey = BytecodeUtil.fieldKey(fi.name);
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, fi),
                        "Non-final static field " + fi.name + " written from tick-reachable method " + mn.name
                                + " — unsynchronized shared mutable state races across concurrently-ticking regions.",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, fi)));
            }
        }
    }
}
