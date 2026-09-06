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
import org.objectweb.asm.Opcodes;

class R12CaptureServerInLambdaTest {

    @Test
    void firesOnMinecraftServerCapturedAndImmediatelyStoredStatic() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR12a", false);
        Bytecode.addStaticField(cw, "isHardcore", "Ljava/util/function/Supplier;", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onServerStarting", false);
        Bytecode.invokeDynamicLambda(mv, "net/minecraft/server/MinecraftServer");
        Bytecode.putStatic(mv, "com/example/mod/BadR12a", "isHardcore", "Ljava/util/function/Supplier;");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnServerLevelCapturedAndStoredStaticWithinLookahead() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR12b", false);
        Bytecode.addStaticField(cw, "cachedLevelFn", "Ljava/util/function/Supplier;", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onLevelLoad", false);
        Bytecode.invokeDynamicLambda(mv, "net/minecraft/server/level/ServerLevel");
        Bytecode.nop(mv);
        Bytecode.putStatic(mv, "com/example/mod/BadR12b", "cachedLevelFn", "Ljava/util/function/Supplier;");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnServerCapturedInEventHandlerStoredStatic() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR12c", false);
        Bytecode.addStaticField(cw, "supplier", "Ljava/util/function/Supplier;", false);
        MethodVisitor mv = Bytecode.beginEventHandler(
                cw, "onServerStarting", "net/neoforged/neoforge/event/server/ServerStartingEvent");
        Bytecode.invokeDynamicLambda(mv, "net/minecraft/server/MinecraftServer");
        Bytecode.putStatic(mv, "com/example/mod/BadR12c", "supplier", "Ljava/util/function/Supplier;");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireWhenNoCapturedServerType() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR12a", false);
        Bytecode.addStaticField(cw, "supplier", "Ljava/util/function/Supplier;", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onServerStarting", false);
        Bytecode.invokeDynamicLambda(mv, "java/lang/String");
        Bytecode.putStatic(mv, "com/example/mod/GoodR12a", "supplier", "Ljava/util/function/Supplier;");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireWhenLambdaStoredInLocalNotStatic() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR12b", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onServerStarting", false);
        Bytecode.invokeDynamicLambda(mv, "net/minecraft/server/MinecraftServer");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireWhenPutstaticIsBeyondLookaheadWindow() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR12c", false);
        Bytecode.addStaticField(cw, "supplier", "Ljava/util/function/Supplier;", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onServerStarting", false);
        Bytecode.invokeDynamicLambda(mv, "net/minecraft/server/MinecraftServer");
        Bytecode.nop(mv);
        Bytecode.nop(mv);
        Bytecode.nop(mv);
        Bytecode.putStatic(mv, "com/example/mod/GoodR12c", "supplier", "Ljava/util/function/Supplier;");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    private static void assertFires(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R12CaptureServerInLambda())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R12"));
    }

    private static void assertDoesNotFire(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R12CaptureServerInLambda())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isEmpty();
    }
}
