/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
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

class R10ThreadStartInModCtorTest {

    @Test
    void firesOnThreadStartInModConstructor() {
        ClassWriter cw = Bytecode.newClassNoCtor("com/example/mod/BadR10a", true);
        MethodVisitor ctor = Bytecode.beginConstructor(cw);
        Bytecode.newAndStartThread(ctor);
        Bytecode.endVoid(ctor);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnExecutorsNewFixedThreadPoolInModConstructor() {
        ClassWriter cw = Bytecode.newClassNoCtor("com/example/mod/BadR10b", true);
        MethodVisitor ctor = Bytecode.beginConstructor(cw);
        Bytecode.invokeStatic(
                ctor,
                "java/util/concurrent/Executors",
                "newFixedThreadPool",
                "()Ljava/util/concurrent/ExecutorService;");
        Bytecode.endVoid(ctor);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnThreadStartInFmlCommonSetupHandler() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR10c", true);
        MethodVisitor mv = Bytecode.beginEventHandler(
                cw, "onCommonSetup", "net/neoforged/fml/event/lifecycle/FMLCommonSetupEvent");
        Bytecode.newAndStartThread(mv);
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireWhenClassIsNotModAnnotated() {
        ClassWriter cw = Bytecode.newClassNoCtor("com/example/mod/GoodR10a", false);
        MethodVisitor ctor = Bytecode.beginConstructor(cw);
        Bytecode.newAndStartThread(ctor);
        Bytecode.endVoid(ctor);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireWhenThreadStartedOutsideInitSurface() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR10b", true);
        MethodVisitor mv = Bytecode.beginMethod(cw, "pollRemoteConfig", false);
        Bytecode.newAndStartThread(mv);
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireOnUnrelatedSetupEventType() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR10c", true);
        MethodVisitor mv = Bytecode.beginEventHandler(
                cw, "onServerStarting", "net/neoforged/neoforge/event/server/ServerStartingEvent");
        Bytecode.newAndStartThread(mv);
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    private static void assertFires(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R10ThreadStartInModCtor())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R10"));
    }

    private static void assertDoesNotFire(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R10ThreadStartInModCtor())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isEmpty();
    }
}
