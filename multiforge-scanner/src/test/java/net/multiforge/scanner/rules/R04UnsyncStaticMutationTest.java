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

class R04UnsyncStaticMutationTest {

    @Test
    void firesOnNonFinalStaticPutFromTickReachableMethodInModClass() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR04", true);
        Bytecode.addStaticField(cw, "activeEffects", "I", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onLevelTick", true);
        Bytecode.putStaticInt(mv, "com/example/mod/BadR04", "activeEffects");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R04UnsyncStaticMutation())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R04"));
        assertThat(findings).allMatch(f -> f.methodName().equals("<field:activeEffects>"));
    }

    @Test
    void doesNotFireWhenClassIsNotModAnnotated() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR04", false);
        Bytecode.addStaticField(cw, "activeEffects", "I", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onLevelTick", true);
        Bytecode.putStaticInt(mv, "com/example/mod/GoodR04", "activeEffects");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R04UnsyncStaticMutation())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isEmpty();
    }
}
