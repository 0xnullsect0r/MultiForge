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
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import net.multiforge.scanner.TickReachability;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R08 — {@code off-thread-BlockEntity-setChanged}. See {@code docs/design/scanner-rules.md}
 * &sect;4 (R08).
 *
 * <p>WARN: a call to {@code BlockEntity.setChanged()} from a method not classified
 * tick-reachable (same heuristic family as R02) — not {@code @RegionThread}, not itself a
 * tick-event handler.
 */
public final class R08OffThreadBlockEntitySetChanged extends AbstractTreeRule {

    private static final String OWNER = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String TARGET_NAME = "setChanged";
    private static final String TARGET_DESC = "()V";

    @Override
    public String id() {
        return "R08";
    }

    @Override
    public String name() {
        return "off-thread-BlockEntity-setChanged";
    }

    @Override
    public String description() {
        return "BlockEntity.setChanged() called from a method not reachable on the region-tick thread.";
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        String classFqn = ctx.className().replace('/', '.');
        var tickReachable = TickReachability.compute(cn);
        for (MethodNode mn : cn.methods) {
            String methodKey = BytecodeUtil.methodKey(mn);
            if (tickReachable.contains(mn.name + mn.desc)) {
                continue;
            }
            for (var insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals(OWNER)
                        || !call.name.equals(TARGET_NAME)
                        || !call.desc.equals(TARGET_DESC)) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        "BlockEntity.setChanged() called from " + mn.name
                                + ", which is not tick-reachable and not annotated @RegionThread — races the"
                                + " owning region worker's dirty-flag writes; wrap in"
                                + " RegionizedTaskQueue.queueChunkTask(...).",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }
}
