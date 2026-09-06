/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R12 — {@code capture-server-in-lambda}. See {@code docs/design/scanner-rules.md} &sect;4 (R12).
 *
 * <p>ERROR: a lambda ({@code invokedynamic} with a {@code LambdaMetafactory} bootstrap) whose
 * captured variables include a typed {@code MinecraftServer} or {@code ServerLevel} parameter,
 * where the resulting lambda instance is itself stored into a {@code static} field ({@code
 * PUTSTATIC} within &le; 2 instructions of the {@code invokedynamic} result).
 */
public final class R12CaptureServerInLambda extends AbstractTreeRule {

    private static final String LAMBDA_METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";
    private static final Set<String> CAPTURE_DESCS =
            Set.of("Lnet/minecraft/server/MinecraftServer;", "Lnet/minecraft/server/level/ServerLevel;");
    private static final int LOOKAHEAD = 2;

    @Override
    public String id() {
        return "R12";
    }

    @Override
    public String name() {
        return "capture-server-in-lambda";
    }

    @Override
    public String description() {
        return "Lambda captures MinecraftServer/ServerLevel and is pinned in a static field — survives"
                + " shutdown and leaks the whole server object graph.";
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        String classFqn = ctx.className().replace('/', '.');
        for (MethodNode mn : cn.methods) {
            String methodKey = BytecodeUtil.methodKey(mn);
            List<AbstractInsnNode> real = realInstructions(mn);
            for (int i = 0; i < real.size(); i++) {
                if (!(real.get(i) instanceof InvokeDynamicInsnNode indy)) {
                    continue;
                }
                if (!isLambdaMetafactoryBootstrap(indy) || !capturesServerType(indy)) {
                    continue;
                }
                if (!followedByPutstatic(real, i)) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, indy),
                        "Lambda in " + mn.name + " captures MinecraftServer/ServerLevel and is stored into a"
                                + " static field — pins the whole server object graph past shutdown.",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, indy)));
            }
        }
    }

    private static boolean isLambdaMetafactoryBootstrap(InvokeDynamicInsnNode indy) {
        return indy.bsm != null && LAMBDA_METAFACTORY_OWNER.equals(indy.bsm.getOwner());
    }

    private static boolean capturesServerType(InvokeDynamicInsnNode indy) {
        for (Type arg : Type.getArgumentTypes(indy.desc)) {
            if (CAPTURE_DESCS.contains(arg.getDescriptor())) {
                return true;
            }
        }
        return false;
    }

    private static boolean followedByPutstatic(List<AbstractInsnNode> real, int indyIndex) {
        int to = Math.min(real.size() - 1, indyIndex + LOOKAHEAD);
        for (int i = indyIndex + 1; i <= to; i++) {
            if (real.get(i) instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTSTATIC) {
                return true;
            }
        }
        return false;
    }

    private static List<AbstractInsnNode> realInstructions(MethodNode mn) {
        List<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions) {
            switch (insn.getType()) {
                case AbstractInsnNode.LABEL, AbstractInsnNode.LINE, AbstractInsnNode.FRAME -> {}
                default -> real.add(insn);
            }
        }
        return real;
    }
}
