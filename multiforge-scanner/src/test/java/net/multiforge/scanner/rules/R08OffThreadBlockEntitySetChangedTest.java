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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.RuleEngine;
import net.multiforge.scanner.testsupport.Bytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

class R08OffThreadBlockEntitySetChangedTest {

    private static final String OWNER = "net/minecraft/world/level/block/entity/BlockEntity";

    @Test
    void firesFromPlainNonTickReachableInstanceMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR08a", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onGuiSlotChanged", false);
        Bytecode.invokeVirtual(mv, OWNER, "setChanged", "()V");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesFromMethodWithUnrelatedAnnotationOnly() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR08b", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onPacketReceived", false);
        Bytecode.invokeVirtual(mv, OWNER, "setChanged", "()V");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesFromNettyCallbackShapedMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR08c", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onDownloadComplete", false);
        Bytecode.invokeVirtual(mv, OWNER, "setChanged", "()V");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireWhenAnnotatedRegionThread() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR08a", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onGuiSlotChanged", true);
        Bytecode.invokeVirtual(mv, OWNER, "setChanged", "()V");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireWhenReachableFromTickEventHandler() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR08b", false);
        MethodVisitor handler =
                Bytecode.beginEventHandler(cw, "onLevelTick", "net/neoforged/neoforge/event/tick/LevelTickEvent");
        Bytecode.invokeVirtual(handler, "com/example/mod/GoodR08b", "helper", "(Ljava/lang/Object;)V");
        Bytecode.endVoid(handler);
        MethodVisitor helper = Bytecode.beginMethod(cw, "helper", false);
        Bytecode.invokeVirtual(helper, OWNER, "setChanged", "()V");
        Bytecode.endVoid(helper);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireOnUnrelatedSetChangedNamedMethodOnDifferentOwner() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR08c", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onGuiSlotChanged", false);
        Bytecode.invokeVirtual(mv, "com/example/mod/MyWidget", "setChanged", "()V");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void firesThroughAModBlockEntitySubclassReceiver() {
        // `this.setChanged()` inside a MyBlockEntity method compiles with the subclass as
        // the invokevirtual owner — the exact-owner check never saw it.
        String myBe = "com/example/mod/MyBlockEntity";
        ClassWriter be = Bytecode.newClass(myBe, false);
        byte[] beBytes = Bytecode.finish(be);

        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR08d", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onDownloadComplete", false);
        Bytecode.invokeVirtual(mv, myBe.replace('.', '/'), "setChanged", "()V");
        Bytecode.endVoid(mv);

        net.multiforge.scanner.TypeHierarchy hierarchy = net.multiforge.scanner.TypeHierarchy.index(List.of(
                new net.multiforge.scanner.TypeHierarchy.ClassInfo(myBe.replace('.', '/'), OWNER, List.of()),
                new net.multiforge.scanner.TypeHierarchy.ClassInfo(
                        "com/example/mod/BadR08d", "java/lang/Object", List.of())));
        List<Finding> findings = new RuleEngine(List.of(new R08OffThreadBlockEntitySetChanged()))
                .scanClassBytes(Bytecode.finish(cw), "test.jar", hierarchy);
        assertThat(findings).hasSize(1);
        assertThat(beBytes).isNotNull();
    }

    @Test
    void doesNotFireInAServerTickCallback() {
        // The vanilla serverTick(BlockState, Level, BlockPos, T) block-entity callback runs
        // on the region that owns the position — same family as R02's game callbacks.
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR08d", false);
        MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "serverTick",
                "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
                null,
                null);
        mv.visitCode();
        Bytecode.invokeVirtual(mv, OWNER, "setChanged", "()V");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireInAHelperCalledFromAScheduledLambda() {
        // context.enqueueWork(() -> this.markDirty()) — the helper the body calls runs on
        // the same thread as the body.
        String host = "com/example/mod/PacketsR08";
        String hostInternal = host.replace('.', '/');
        ClassWriter cw = Bytecode.newClass(host, false);
        MethodVisitor handler = Bytecode.beginMethod(cw, "handle", false);
        handler.visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                org.objectweb.asm.Type.getType("()V"),
                new org.objectweb.asm.Handle(
                        org.objectweb.asm.Opcodes.H_INVOKESTATIC, hostInternal, "lambda$handle$0", "()V", false),
                org.objectweb.asm.Type.getType("()V"));
        handler.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKEINTERFACE,
                "net/neoforged/neoforge/network/handling/IPayloadContext",
                "enqueueWork",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
                true);
        handler.visitInsn(org.objectweb.asm.Opcodes.POP);
        Bytecode.endVoid(handler);
        MethodVisitor body = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PRIVATE
                        | org.objectweb.asm.Opcodes.ACC_STATIC
                        | org.objectweb.asm.Opcodes.ACC_SYNTHETIC,
                "lambda$handle$0",
                "()V",
                null,
                null);
        body.visitCode();
        body.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, hostInternal, "markDirty", "()V", false);
        body.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        body.visitMaxs(0, 0);
        body.visitEnd();
        MethodVisitor helper = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PRIVATE | org.objectweb.asm.Opcodes.ACC_STATIC,
                "markDirty",
                "()V",
                null,
                null);
        helper.visitCode();
        Bytecode.invokeVirtual(helper, OWNER, "setChanged", "()V");
        Bytecode.endVoid(helper);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    private static final org.objectweb.asm.Handle LAMBDA_METAFACTORY = new org.objectweb.asm.Handle(
            org.objectweb.asm.Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;",
            false);

    private static void assertFires(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R08OffThreadBlockEntitySetChanged())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R08"));
    }

    private static void assertDoesNotFire(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R08OffThreadBlockEntitySetChanged())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isEmpty();
    }
}
