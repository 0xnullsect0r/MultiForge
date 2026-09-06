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

class R11ReflectOnNeoforgedInternalTest {

    @Test
    void firesOnGetDeclaredFieldViaVanillaClassLiteral() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR11a", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "peekMailbox", false);
        Bytecode.ldcClassLiteral(mv, "net/minecraft/server/level/ChunkMap");
        Bytecode.invokeVirtual(
                mv, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnGetDeclaredMethodViaForNameStringOnNeoforgeInternal() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR11b", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "invokeInternal", false);
        Bytecode.ldcString(mv, "net.neoforged.neoforge.internal.InternalDispatcher");
        Bytecode.invokeVirtual(
                mv, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;)Ljava/lang/reflect/Method;");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnSetAccessibleAfterVanillaClassLiteral() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR11c", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "unlock", false);
        Bytecode.ldcClassLiteral(mv, "net/minecraft/world/entity/Entity");
        Bytecode.invokeVirtual(mv, "java/lang/reflect/Field", "setAccessible", "(Z)V");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireOnGetDeclaredFieldWithNoPrecedingInternalLdc() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR11a", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "peekOwnField", false);
        Bytecode.invokeVirtual(
                mv, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireOnGetDeclaredFieldForModOwnClassLiteral() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR11b", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "peekOwnField", false);
        Bytecode.ldcClassLiteral(mv, "com/example/mod/MyBlockEntity");
        Bytecode.invokeVirtual(
                mv, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireWhenInternalLdcIsNotFollowedByAReflectiveCall() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR11c", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "logInternalName", false);
        Bytecode.ldcClassLiteral(mv, "net/minecraft/server/level/ChunkMap");
        Bytecode.invokeVirtual(mv, "java/lang/Object", "toString", "()Ljava/lang/String;");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    private static void assertFires(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R11ReflectOnNeoforgedInternal())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R11"));
    }

    private static void assertDoesNotFire(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R11ReflectOnNeoforgedInternal())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isEmpty();
    }
}
