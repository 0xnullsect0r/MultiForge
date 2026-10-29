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

class R02OffThreadLevelSetBlockTest {

    private static final String OWNER = "net/minecraft/world/level/Level";

    @Test
    void firesOnSetBlockFromNonTickReachableMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR02", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onDownloadComplete", false);
        Bytecode.invokeVirtual(mv, OWNER, "setBlock", "()Z");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R02OffThreadLevelSetBlock())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R02"));
    }

    @Test
    void doesNotFireWhenMethodIsRegionThread() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR02", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", true);
        Bytecode.invokeVirtual(mv, OWNER, "setBlock", "()Z");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R02OffThreadLevelSetBlock())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isEmpty();
    }

    @Test
    void isAWarningMultiForgeReroutesItAtRuntime() {
        assertThat(new R02OffThreadLevelSetBlock().severity()).isEqualTo(net.multiforge.scanner.Severity.WARN);
    }

    @Test
    void doesNotFireInABlockCallbackThatTakesTheLevel() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/MyBlock", false);
        MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "neighborChanged",
                "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;)V",
                null,
                null);
        mv.visitCode();
        Bytecode.invokeVirtual(mv, OWNER, "setBlock", "()Z");
        Bytecode.endVoid(mv);
        assertThat(new RuleEngine(List.of(new R02OffThreadLevelSetBlock()))
                        .scanClassBytes(Bytecode.finish(cw), "test.jar"))
                .isEmpty();
    }

    @Test
    void doesNotFireInALambdaHandedToEnqueueWork() {
        // A packet handler: context.enqueueWork(() -> level.setBlock(...)) is the documented fix.
        String host = "com/example/mod/Packets";
        ClassWriter cw = Bytecode.newClass(host, false);
        MethodVisitor handler = Bytecode.beginMethod(cw, "handle", false);
        handler.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        handler.visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                org.objectweb.asm.Type.getType("()V"),
                new org.objectweb.asm.Handle(
                        org.objectweb.asm.Opcodes.H_INVOKESTATIC, host, "lambda$handle$0", "()V", false),
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
        Bytecode.invokeVirtual(body, OWNER, "setBlock", "()Z");
        Bytecode.endVoid(body);
        assertThat(new RuleEngine(List.of(new R02OffThreadLevelSetBlock()))
                        .scanClassBytes(Bytecode.finish(cw), "test.jar"))
                .isEmpty();
    }

    @Test
    void stillFiresInALambdaHandedToAPlainExecutor() {
        String host = "com/example/mod/Async";
        ClassWriter cw = Bytecode.newClass(host, false);
        MethodVisitor m = Bytecode.beginMethod(cw, "kickOff", false);
        m.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        m.visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                org.objectweb.asm.Type.getType("()V"),
                new org.objectweb.asm.Handle(
                        org.objectweb.asm.Opcodes.H_INVOKESTATIC, host, "lambda$kickOff$0", "()V", false),
                org.objectweb.asm.Type.getType("()V"));
        m.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKEINTERFACE,
                "java/util/concurrent/ExecutorService",
                "submit",
                "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;",
                true);
        m.visitInsn(org.objectweb.asm.Opcodes.POP);
        Bytecode.endVoid(m);
        MethodVisitor body = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PRIVATE
                        | org.objectweb.asm.Opcodes.ACC_STATIC
                        | org.objectweb.asm.Opcodes.ACC_SYNTHETIC,
                "lambda$kickOff$0",
                "()V",
                null,
                null);
        body.visitCode();
        Bytecode.invokeVirtual(body, OWNER, "setBlock", "()Z");
        Bytecode.endVoid(body);
        assertThat(new RuleEngine(List.of(new R02OffThreadLevelSetBlock()))
                        .scanClassBytes(Bytecode.finish(cw), "test.jar"))
                .hasSize(1);
    }

    private static final org.objectweb.asm.Handle LAMBDA_METAFACTORY = new org.objectweb.asm.Handle(
            org.objectweb.asm.Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;",
            false);
}
