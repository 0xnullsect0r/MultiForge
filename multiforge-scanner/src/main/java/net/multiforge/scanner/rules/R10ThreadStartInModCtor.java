/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner.rules;

import java.util.List;
import java.util.function.Consumer;
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R10 — {@code thread-start-in-mod-ctor}. See {@code docs/design/scanner-rules.md} &sect;4 (R10).
 *
 * <p>WARN: a {@code new Thread(...)} allocation followed by {@code .start()} (or a direct {@code
 * Executors.new*} construction) inside a {@code @Mod} class's constructor or a method reachable
 * from {@code FMLCommonSetupEvent}/{@code FMLClientSetupEvent} — the standard mod-init surface.
 */
public final class R10ThreadStartInModCtor extends AbstractTreeRule {

    private static final String THREAD_OWNER = "java/lang/Thread";
    private static final String EXECUTORS_OWNER = "java/util/concurrent/Executors";

    @Override
    public String id() {
        return "R10";
    }

    @Override
    public String name() {
        return "thread-start-in-mod-ctor";
    }

    @Override
    public String description() {
        return "Raw Thread/Executors construction inside a @Mod constructor or setup-event handler.";
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
        for (MethodNode mn : cn.methods) {
            if (!isModInitSurface(mn)) {
                continue;
            }
            String methodKey = BytecodeUtil.methodKey(mn);
            for (var insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                boolean threadStart = call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(THREAD_OWNER)
                        && call.name.equals("start");
                boolean executorsNew = call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(EXECUTORS_OWNER)
                        && call.name.startsWith("new");
                if (!threadStart && !executorsNew) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        (threadStart ? "new Thread(...).start()" : "Executors." + call.name + call.desc)
                                + " in " + mn.name
                                + " spawns a thread invisible to MultiForge's scheduler — use AsyncScheduler"
                                + " instead so it can be cancelled on mod unload.",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }

    private static boolean isModInitSurface(MethodNode mn) {
        if (mn.name.equals("<init>")) {
            return true;
        }
        if (!hasSubscribeEventAnnotation(mn)) {
            return false;
        }
        for (Type arg : Type.getArgumentTypes(mn.desc)) {
            if (arg.getSort() != Type.OBJECT) {
                continue;
            }
            String internalName = arg.getInternalName();
            if (internalName.endsWith("FMLCommonSetupEvent") || internalName.endsWith("FMLClientSetupEvent")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSubscribeEventAnnotation(MethodNode mn) {
        return hasSuffix(mn.visibleAnnotations, "SubscribeEvent;")
                || hasSuffix(mn.invisibleAnnotations, "SubscribeEvent;");
    }

    private static boolean hasSuffix(List<AnnotationNode> nodes, String suffix) {
        if (nodes == null) {
            return false;
        }
        for (AnnotationNode n : nodes) {
            if (n.desc.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
