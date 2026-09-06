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
 * R01 — {@code direct-ChunkMap-invoke}. See {@code docs/design/scanner-rules.md} &sect;4 (R01).
 *
 * <p>WARN: mod bytecode that reaches past {@code ChunkSource}/{@code ServerChunkCache}'s public
 * surface directly into {@code net.minecraft.server.level.ChunkMap} internals, instead of the
 * {@code MultiForgeChunkMap} facade.
 */
public final class R01DirectChunkMapInvoke extends AbstractTreeRule {

    private static final String OWNER = "net/minecraft/server/level/ChunkMap";
    private static final Set<String> PUBLIC_STABLE_API = Set.of("getServer", "level");

    @Override
    public String id() {
        return "R01";
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
            for (var insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                boolean virtualOrInterface =
                        call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE;
                if (!virtualOrInterface || !call.owner.equals(OWNER) || PUBLIC_STABLE_API.contains(call.name)) {
                    continue;
                }
                String methodKey = BytecodeUtil.methodKey(mn);
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        "Direct call to ChunkMap." + call.name + call.desc
                                + " bypasses the MultiForgeChunkMap facade — use ChunkSource's stable API instead.",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }
}
