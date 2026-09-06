/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner.rules;

import java.util.Set;
import java.util.function.Consumer;
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R05 — {@code entity-setpos-off-coord}. See {@code docs/design/scanner-rules.md} &sect;4 (R05).
 *
 * <p>ERROR: a direct call to {@code Entity.setPos}/{@code setPosRaw} from mod code, not made
 * from within {@code net.multiforge.runtime.entity.EntityMigrationCoordinator} itself.
 */
public final class R05EntitySetPosOffCoord extends AbstractTreeRule {

    private static final String OWNER = "net/minecraft/world/entity/Entity";
    private static final Set<String> TARGET_NAMES = Set.of("setPos", "setPosRaw");
    private static final String COORDINATOR_PACKAGE = "net/multiforge/runtime/entity/";

    @Override
    public String id() {
        return "R05";
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        if (ctx.className().startsWith(COORDINATOR_PACKAGE)) {
            return;
        }
        String classFqn = ctx.className().replace('/', '.');
        for (MethodNode mn : cn.methods) {
            String methodKey = BytecodeUtil.methodKey(mn);
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
                        "Entity." + call.name + call.desc + " called directly from " + mn.name
                                + " — cross-region entity movement must go through EntityMigrationCoordinator.",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }
}
