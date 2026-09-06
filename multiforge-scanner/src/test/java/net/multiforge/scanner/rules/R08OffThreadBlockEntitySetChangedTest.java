/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
