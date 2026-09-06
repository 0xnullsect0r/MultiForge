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

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import net.multiforge.scanner.TickReachability;
import net.multiforge.scanner.TypeHierarchy;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R03 — {@code blocking-future}. See {@code docs/design/scanner-rules.md} &sect;4 (R03).
 *
 * <p>ERROR: a blocking wait method ({@code .get()}, {@code .get(long, TimeUnit)}, {@code
 * .join()}, {@code .getNow(Object)}, {@code .awaitUninterruptibly()}) invoked on a receiver whose
 * static type is, or is a subtype of, {@code java.util.concurrent.Future}, {@code
 * java.util.concurrent.CompletionStage}, or {@code java.util.concurrent.ForkJoinTask} — lexically
 * inside a method annotated {@code @RegionThread}. Direct bytecode expression of CLAUDE.md rule
 * 4.
 *
 * <p>Subtype resolution walks a per-scan {@link TypeHierarchy} (round-6 fork C HIGH finding: a
 * literal two-name owner check misses a mod-defined receiver like {@code class MyFuture extends
 * CompletableFuture<T>} — the bytecode owner for {@code myFuture.join()} is {@code MyFuture}
 * itself, not {@code CompletableFuture}). {@code TypeHierarchy} resolves such subtypes against
 * the in-jar class index {@code RuleEngine} builds in its first pass, falling back to real
 * classpath reflection for JDK-only ancestors (e.g. {@code CompletableFuture} itself implementing
 * {@code Future} and {@code CompletionStage}). A chained idiom like {@code
 * future.orTimeout(...).join()} is caught because the trailing {@code .join()} is its own {@code
 * INVOKEVIRTUAL} against the (still Future-typed) result — {@code orTimeout} itself doesn't block
 * and is not flagged.
 */
public final class R03BlockingFuture extends AbstractTreeRule {

    /**
     * Any receiver whose static type is, or transitively extends/implements, one of these is a
     * blocking-wait target.
     */
    private static final Set<String> TARGET_SUPERTYPES = Set.of(
            "java/util/concurrent/Future", "java/util/concurrent/CompletionStage", "java/util/concurrent/ForkJoinTask");

    private static final Set<String> NAMES = Set.of("get", "join", "getNow", "awaitUninterruptibly");

    @Override
    public String id() {
        return "R03";
    }

    @Override
    public String name() {
        return "blocking-future";
    }

    @Override
    public String description() {
        return "Future/CompletionStage/ForkJoinTask blocking wait (get/join/getNow/"
                + "awaitUninterruptibly) called from a @RegionThread method.";
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        String classFqn = ctx.className().replace('/', '.');
        TypeHierarchy hierarchy = ctx.typeHierarchy();
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
                if (!invokable
                        || !NAMES.contains(call.name)
                        || !hierarchy.isSubtypeOfAny(call.owner, TARGET_SUPERTYPES)) {
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
