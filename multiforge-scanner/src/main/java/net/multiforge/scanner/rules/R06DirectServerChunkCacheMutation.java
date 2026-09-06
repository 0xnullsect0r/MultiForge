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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R06 — {@code direct-ServerChunkCache-mutation}. See {@code docs/design/scanner-rules.md}
 * &sect;4 (R06).
 *
 * <p>WARN: mod calls to {@code ServerChunkCache}'s mutating surface ({@code addRegionTicket},
 * {@code removeRegionTicket}, {@code updateChunkForced}, and any void-returning method not named
 * {@code get*}/{@code is*}/{@code has*}) bypassing {@code ServerChunkCacheDelegate}.
 */
public final class R06DirectServerChunkCacheMutation extends AbstractTreeRule {

    private static final String OWNER = "net/minecraft/server/level/ServerChunkCache";
    private static final Set<String> NAMED_MUTATORS =
            Set.of("addRegionTicket", "removeRegionTicket", "updateChunkForced");

    @Override
    public String id() {
        return "R06";
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        if (ctx.className().startsWith("net/multiforge/") || ctx.className().startsWith("net/minecraft/")) {
            return;
        }
        String classFqn = ctx.className().replace('/', '.');
        for (MethodNode mn : cn.methods) {
            String methodKey = BytecodeUtil.methodKey(mn);
            for (var insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !call.owner.equals(OWNER)) {
                    continue;
                }
                if (!isMutating(call.name, call.desc)) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        "ServerChunkCache." + call.name + call.desc + " called directly from " + mn.name
                                + " — bypasses ServerChunkCacheDelegate's per-region ticket bookkeeping.",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }

    private static boolean isMutating(String name, String desc) {
        if (NAMED_MUTATORS.contains(name)) {
            return true;
        }
        boolean returnsVoid = Type.getReturnType(desc).equals(Type.VOID_TYPE);
        boolean looksLikeAccessor = name.startsWith("get") || name.startsWith("is") || name.startsWith("has");
        return returnsVoid && !looksLikeAccessor;
    }
}
