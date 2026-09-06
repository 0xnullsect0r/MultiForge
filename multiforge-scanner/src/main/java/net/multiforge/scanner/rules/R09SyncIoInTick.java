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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R09 — {@code sync-io-in-tick}. See {@code docs/design/scanner-rules.md} &sect;4 (R09).
 *
 * <p>ERROR: a call to a fixed, extendable synchronous-disk-I/O allowlist inside a method the
 * tick-reachability heuristic (&sect;1.4) classifies as region-tick-reachable. The allowlist
 * (round-6 fork C HIGH finding — the previous two-owner, three-exact-name list missed common
 * idioms) covers:
 *
 * <ul>
 *   <li>any {@code read*}/{@code write*}/{@code transferTo} call on {@link #STREAM_PREFIX_OWNERS}
 *       — {@code InputStream}/{@code OutputStream} and their {@code File}/{@code Data}/{@code
 *       Buffered} subclasses, so a wrapped stream (e.g. {@code new
 *       BufferedInputStream(fileInputStream)}) is still caught;
 *   <li>any {@code read*}/{@code write*} call on {@link #CHANNEL_PREFIX_OWNERS} ({@code
 *       FileChannel}, {@code AsynchronousFileChannel});
 *   <li>every {@code RandomAccessFile} method — opening or operating on one is inherently
 *       synchronous disk I/O, not just its {@code read*} methods;
 *   <li>{@code java.nio.file.Files}: {@code readAllBytes}, {@code readString}, {@code
 *       newInputStream} (existing), plus {@code lines}, {@code readAllLines}, {@code
 *       newBufferedReader}, {@code newBufferedWriter} — matched by method name only, so every
 *       overload (e.g. {@code readString(Path, Charset)}) is covered without enumerating
 *       descriptors. {@code Files.write}/{@code Files.newOutputStream} stay off this list
 *       deliberately (see {@code R09SyncIoInTickTest#doesNotFireOnFilesWriteInRegionThreadMethod}
 *       — narrowing scope for those is a separate call, not part of this fix).
 * </ul>
 */
public final class R09SyncIoInTick extends AbstractTreeRule {

    /**
     * {@code java.io} stream types where any {@code read*}/{@code write*}/{@code transferTo}
     * method is synchronous disk I/O — includes wrapper types (e.g. {@code
     * BufferedInputStream}) so a wrapped {@code FileInputStream} read is still caught.
     */
    private static final Set<String> STREAM_PREFIX_OWNERS = Set.of(
            "java/io/InputStream",
            "java/io/OutputStream",
            "java/io/FileInputStream",
            "java/io/FileOutputStream",
            "java/io/DataInputStream",
            "java/io/DataOutputStream",
            "java/io/BufferedInputStream",
            "java/io/BufferedOutputStream");

    /** {@code java.nio.channels} file-channel types where any {@code read*}/{@code write*} method is synchronous disk I/O. */
    private static final Set<String> CHANNEL_PREFIX_OWNERS =
            Set.of("java/nio/channels/FileChannel", "java/nio/channels/AsynchronousFileChannel");

    /** Every method on {@code RandomAccessFile} — including {@code <init>} — is synchronous disk I/O. */
    private static final String RANDOM_ACCESS_FILE_OWNER = "java/io/RandomAccessFile";

    private static final String FILES_OWNER = "java/nio/file/Files";
    private static final Set<String> FILES_EXACT_NAMES = Set.of(
            "readAllBytes",
            "readString",
            "newInputStream",
            "lines",
            "readAllLines",
            "newBufferedReader",
            "newBufferedWriter");

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
        if (call.owner.equals(RANDOM_ACCESS_FILE_OWNER)) {
            return true;
        }
        if (STREAM_PREFIX_OWNERS.contains(call.owner)
                && (call.name.startsWith("read") || call.name.startsWith("write") || call.name.equals("transferTo"))) {
            return true;
        }
        if (CHANNEL_PREFIX_OWNERS.contains(call.owner)
                && (call.name.startsWith("read") || call.name.startsWith("write"))) {
            return true;
        }
        return call.owner.equals(FILES_OWNER) && FILES_EXACT_NAMES.contains(call.name);
    }
}
