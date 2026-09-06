/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner.testsupport;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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

    // ---- R07-R12 additions below ----

    /** Like {@link #newClass}, but skips the automatic trivial constructor — for fixtures that need a
     * hand-built {@code <init>} body (R10's "bad pattern lives in the constructor" shape). */
    public static ClassWriter newClassNoCtor(String internalName, boolean withModAnnotation) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        if (withModAnnotation) {
            cw.visitAnnotation(MOD_ANNOTATION_DESC, true).visitEnd();
        }
        return cw;
    }

    /** Begins a hand-built {@code <init>()V} that has already called {@code super()}. Caller adds body, then {@link #endVoid}. */
    public static MethodVisitor beginConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        return mv;
    }

    /**
     * Begins an event-handler-shaped method: {@code @SubscribeEvent} plus a single parameter of
     * the given internal type name (used for both NeoForge tick-event and mod-setup-event
     * fixtures — R02/R04/R08/R09's tick-reachability seed, and R10's FMLCommonSetupEvent/
     * FMLClientSetupEvent seed).
     */
    public static MethodVisitor beginEventHandler(ClassWriter cw, String name, String eventParamInternalName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "(L" + eventParamInternalName + ";)V", null, null);
        mv.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true).visitEnd();
        mv.visitCode();
        return mv;
    }

    /** Emits {@code INVOKESTATIC owner.name desc ; [POP if non-void]} — no receiver/args pushed (see class javadoc). */
    public static void invokeStatic(MethodVisitor mv, String owner, String name, String desc) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false);
        if (!desc.endsWith(")V")) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    /** Emits {@code NEW Thread ; DUP ; INVOKESPECIAL <init>()V ; INVOKEVIRTUAL start()V} — stack-balanced, no trailing POP needed. */
    public static void newAndStartThread(MethodVisitor mv) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/Thread");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
    }

    /** Emits {@code LDC <class-literal-for-internalName>} then discards it — a pure "marker" instruction for R11's def-use scan. */
    public static void ldcClassLiteral(MethodVisitor mv, String internalName) {
        mv.visitLdcInsn(Type.getObjectType(internalName));
        mv.visitInsn(Opcodes.POP);
    }

    /** Emits {@code LDC <string>} then discards it — a pure "marker" instruction for R11's def-use scan. */
    public static void ldcString(MethodVisitor mv, String value) {
        mv.visitLdcInsn(value);
        mv.visitInsn(Opcodes.POP);
    }

    /** {@code NOP} — filler instruction to push a PUTSTATIC outside R12's &le;2-instruction lookahead window. */
    public static void nop(MethodVisitor mv) {
        mv.visitInsn(Opcodes.NOP);
    }

    /**
     * Emits an {@code invokedynamic} with a {@code LambdaMetafactory} bootstrap, leaving the
     * resulting functional-interface instance on the stack. When {@code capturedParamInternalName}
     * is non-null, the call site is shaped as capturing one value of that type (R12's "typed
     * MinecraftServer/ServerLevel parameter" — a fake {@code ACONST_NULL} stands in for the real
     * captured local, since these fixtures are never executed).
     */
    public static void invokeDynamicLambda(MethodVisitor mv, String capturedParamInternalNameOrNull) {
        String paramDesc = capturedParamInternalNameOrNull == null ? "" : "L" + capturedParamInternalNameOrNull + ";";
        if (capturedParamInternalNameOrNull != null) {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        String indyDesc = "(" + paramDesc + ")Ljava/util/function/Supplier;";
        Handle bsm = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Handle implMethod = new Handle(
                Opcodes.H_INVOKESTATIC, "com/example/mod/LambdaImplHost", "lambda$impl", "()Ljava/lang/Object;", false);
        mv.visitInvokeDynamicInsn(
                "get",
                indyDesc,
                bsm,
                Type.getType("()Ljava/lang/Object;"),
                implMethod,
                Type.getType("()Ljava/lang/Object;"));
    }

    /** Emits {@code PUTSTATIC owner.name desc} — consumes whatever the previous instruction left on the stack. */
    public static void putStatic(MethodVisitor mv, String owner, String name, String desc) {
        mv.visitFieldInsn(Opcodes.PUTSTATIC, owner, name, desc);
    }
}
