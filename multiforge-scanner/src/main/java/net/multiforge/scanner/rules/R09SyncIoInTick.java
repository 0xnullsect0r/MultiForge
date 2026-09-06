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
import net.multiforge.scanner.TickReachability;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R09 — {@code sync-io-in-tick}. See {@code docs/design/scanner-rules.md} &sect;4 (R09).
 *
 * <p>ERROR: a call to a fixed, extendable synchronous-disk-I/O allowlist ({@code
 * FileInputStream.read*}, {@code RandomAccessFile.read*}, {@code Files.readAllBytes}, {@code
 * Files.readString}, {@code Files.newInputStream}) inside a method the tick-reachability
 * heuristic (&sect;1.4) classifies as region-tick-reachable.
 */
public final class R09SyncIoInTick extends AbstractTreeRule {

    /** {@code owner -> "read"-prefixed method match} entries. */
    private static final List<String> READ_PREFIX_OWNERS =
            List.of("java/io/FileInputStream", "java/io/RandomAccessFile");

    private static final String FILES_OWNER = "java/nio/file/Files";
    private static final List<String> FILES_EXACT_NAMES = List.of("readAllBytes", "readString", "newInputStream");

    @Override
    public String id() {
        return "R09";
    }

    @Override
    public String name() {
        return "sync-io-in-tick";
    }

    @Override
    public String description() {
        return "Synchronous disk I/O call inside a region-tick-reachable method.";
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
            if (!tickReachable.contains(mn.name + mn.desc)) {
                continue;
            }
            String methodKey = BytecodeUtil.methodKey(mn);
            for (var insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (!isSyncIoCall(call)) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name
                                + " performs synchronous disk I/O on the region-tick thread (called from " + mn.name
                                + ") — head-of-line-blocks the region; move to AsyncScheduler.runNow(...).",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }

    private static boolean isSyncIoCall(MethodInsnNode call) {
        if (READ_PREFIX_OWNERS.contains(call.owner) && call.name.startsWith("read")) {
            return true;
        }
        return call.owner.equals(FILES_OWNER) && FILES_EXACT_NAMES.contains(call.name);
    }
}
