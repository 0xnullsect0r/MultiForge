/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner.rules;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import net.multiforge.scanner.TickReachability;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R03 — {@code blocking-future}. See {@code docs/design/scanner-rules.md} &sect;4 (R03).
 *
 * <p>ERROR: {@code .get()}, {@code .get(long, TimeUnit)}, or {@code .join()} invoked on a {@code
 * CompletableFuture}/{@code Future} receiver, lexically inside a method annotated {@code
 * @RegionThread}. Direct bytecode expression of CLAUDE.md rule 4.
 */
public final class R03BlockingFuture extends AbstractTreeRule {

    private static final Set<String> OWNERS =
            Set.of("java/util/concurrent/CompletableFuture", "java/util/concurrent/Future");
    private static final Set<String> NAMES = Set.of("get", "join");

    @Override
    public String id() {
        return "R03";
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        String classFqn = ctx.className().replace('/', '.');
        for (MethodNode mn : cn.methods) {
            if (!isRegionThread(mn)) {
                continue;
            }
            String methodKey = BytecodeUtil.methodKey(mn);
            for (var insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                boolean invokable =
                        call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE;
                if (!invokable || !OWNERS.contains(call.owner) || !NAMES.contains(call.name)) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name
                                + " blocks the region worker — called from @RegionThread method " + mn.name,
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }

    private static boolean isRegionThread(MethodNode mn) {
        return hasDesc(mn.visibleAnnotations, TickReachability.REGION_THREAD_DESC)
                || hasDesc(mn.invisibleAnnotations, TickReachability.REGION_THREAD_DESC);
    }

    private static boolean hasDesc(List<AnnotationNode> nodes, String desc) {
        if (nodes == null) {
            return false;
        }
        for (AnnotationNode n : nodes) {
            if (desc.equals(n.desc)) {
                return true;
            }
        }
        return false;
    }
}
