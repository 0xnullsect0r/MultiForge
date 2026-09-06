/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner.testsupport;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Tiny in-memory bytecode fixture builder shared by the per-rule tests. Fixtures are built
 * directly with {@link ClassWriter} (no javac, no committed binary/jar fixtures) — see the
 * Track C2.1-7 plan's "test corpus" note.
 *
 * <p>Fixture methods only need to be structurally parseable by {@code ClassReader} (the rules
 * never load or execute these classes), so call sites push a dummy receiver/args as needed for
 * stack-depth bookkeeping under {@link ClassWriter#COMPUTE_MAXS} without regard for real JVM
 * verification.
 */
public final class Bytecode {

    public static final String MOD_ANNOTATION_DESC = "Lnet/neoforged/fml/common/Mod;";
    public static final String REGION_THREAD_DESC = "Lnet/multiforge/api/RegionThread;";

    private Bytecode() {}

    public static ClassWriter newClass(String internalName, boolean withModAnnotation) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        if (withModAnnotation) {
            cw.visitAnnotation(MOD_ANNOTATION_DESC, true).visitEnd();
        }
        addDefaultConstructor(cw);
        return cw;
    }

    public static void addDefaultConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    public static void addStaticField(ClassWriter cw, String name, String desc, boolean isFinal) {
        int access = Opcodes.ACC_STATIC | (isFinal ? Opcodes.ACC_FINAL : 0);
        cw.visitField(access, name, desc, null, null).visitEnd();
    }

    /** Begins an instance method, optionally annotated {@code @RegionThread}. Caller must call {@link #endVoid}. */
    public static MethodVisitor beginMethod(ClassWriter cw, String name, boolean regionThread) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "(Ljava/lang/Object;)V", null, null);
        if (regionThread) {
            mv.visitAnnotation(REGION_THREAD_DESC, true).visitEnd();
        }
        mv.visitCode();
        return mv;
    }

    /** Emits {@code ACONST_NULL ; INVOKEVIRTUAL owner.name desc ; [POP if non-void]}. */
    public static void invokeVirtual(MethodVisitor mv, String owner, String name, String desc) {
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
        if (!desc.endsWith(")V")) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    /** Emits {@code ACONST_NULL ; INVOKEINTERFACE owner.name desc ; [POP if non-void]}. */
    public static void invokeInterface(MethodVisitor mv, String owner, String name, String desc) {
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, name, desc, true);
        if (!desc.endsWith(")V")) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    /** Emits {@code ICONST_1 ; PUTSTATIC owner.name desc} for an {@code int} field. */
    public static void putStaticInt(MethodVisitor mv, String owner, String name) {
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, name, "I");
    }

    public static void endVoid(MethodVisitor mv) {
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    public static byte[] finish(ClassWriter cw) {
        cw.visitEnd();
        return cw.toByteArray();
    }
}
