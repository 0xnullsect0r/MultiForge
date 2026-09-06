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

import java.util.Set;
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
 * R02 — {@code off-thread-Level.setBlock}. See {@code docs/design/scanner-rules.md} &sect;4 (R02).
 *
 * <p>ERROR: a call to {@code Level.setBlock} (or overloads) from a method the tick-reachability
 * heuristic classifies as running off the region-tick thread.
 */
public final class R02OffThreadLevelSetBlock extends AbstractTreeRule {

    private static final String OWNER = "net/minecraft/world/level/Level";
    private static final Set<String> TARGET_NAMES = Set.of("setBlock");

    @Override
    public String id() {
        return "R02";
    }

    @Override
    public String name() {
        return "off-thread-Level.setBlock";
    }

    @Override
    public String description() {
        return "Level.setBlock called from a method not reachable on the region-tick thread.";
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
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
                        || !TARGET_NAMES.contains(call.name)) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        "Level." + call.name + call.desc + " called from " + mn.name
                                + ", which is not tick-reachable and not annotated @RegionThread — wrap in"
                                + " RegionizedTaskQueue.queueChunkTask(...).",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }
}
