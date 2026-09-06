/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner.rules;

import java.util.function.Consumer;
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R11 — {@code reflect-on-neoforged-internal}. See {@code docs/design/scanner-rules.md} &sect;4
 * (R11).
 *
 * <p>WARN: {@code Class.getDeclaredField}, {@code Class.getDeclaredMethod}, or {@code
 * Field.setAccessible(true)}/{@code Method.setAccessible(true)} calls where a preceding {@code
 * LDC} in the same method — either a class-literal ({@code Foo.class}, an ASM {@link Type}
 * constant) or a {@code Class.forName(String)}-style dotted-name string constant — names a class
 * under {@code net.neoforged.neoforge.*} or {@code net.minecraft.*}. Matched by local def-use
 * within the same method, linear scan order (doc's "immediately preceding" is read loosely here:
 * the field-name-string {@code LDC} that {@code getDeclaredField("name")} also pushes sits
 * between the class-identifying {@code LDC} and the call, so a strict "immediately preceding"
 * window would miss the buggy example in the doc itself).
 */
public final class R11ReflectOnNeoforgedInternal extends AbstractTreeRule {

    private static final String CLASS_OWNER = "java/lang/Class";
    private static final String FIELD_OWNER = "java/lang/reflect/Field";
    private static final String METHOD_OWNER = "java/lang/reflect/Method";

    @Override
    public String id() {
        return "R11";
    }

    @Override
    public String name() {
        return "reflect-on-neoforged-internal";
    }

    @Override
    public String description() {
        return "Reflection (getDeclaredField/getDeclaredMethod/setAccessible) targeting a NeoForge or Vanilla"
                + " internal class.";
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        String classFqn = ctx.className().replace('/', '.');
        for (MethodNode mn : cn.methods) {
            String methodKey = BytecodeUtil.methodKey(mn);
            String pendingTarget = null;
            for (var insn : mn.instructions) {
                if (insn instanceof LdcInsnNode ldc) {
                    String matched = matchInternalTarget(ldc);
                    if (matched != null) {
                        pendingTarget = matched;
                    }
                    continue;
                }
                if (!(insn instanceof MethodInsnNode call) || pendingTarget == null) {
                    continue;
                }
                if (!isReflectiveCall(call)) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name
                                + " reflects into " + pendingTarget + " (called from " + mn.name
                                + ") — bypasses every static call-site check and is one refactor from"
                                + " NoSuchFieldException; use the stable public API instead.",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
                pendingTarget = null;
            }
        }
    }

    /** @return the dotted internal target class name if {@code ldc} names one under net.neoforged.neoforge.* or net.minecraft.*, else null. */
    private static String matchInternalTarget(LdcInsnNode ldc) {
        if (ldc.cst instanceof Type t && t.getSort() == Type.OBJECT) {
            String internal = t.getInternalName();
            if (internal.startsWith("net/neoforged/neoforge/") || internal.startsWith("net/minecraft/")) {
                return internal.replace('/', '.');
            }
        } else if (ldc.cst instanceof String s) {
            if (s.startsWith("net.neoforged.neoforge.") || s.startsWith("net.minecraft.")) {
                return s;
            }
        }
        return null;
    }

    private static boolean isReflectiveCall(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL) {
            return false;
        }
        if (call.owner.equals(CLASS_OWNER)) {
            return call.name.equals("getDeclaredField") || call.name.equals("getDeclaredMethod");
        }
        if (call.owner.equals(FIELD_OWNER) || call.owner.equals(METHOD_OWNER)) {
            return call.name.equals("setAccessible") && call.desc.equals("(Z)V");
        }
        return false;
    }
}
