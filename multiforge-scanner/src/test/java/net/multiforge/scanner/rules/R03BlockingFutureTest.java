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

class R03BlockingFutureTest {

    private static final String OWNER = "java/util/concurrent/CompletableFuture";

    @Test
    void firesOnGetInsideRegionThreadMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR03", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", true);
        Bytecode.invokeVirtual(mv, OWNER, "get", "()Ljava/lang/Object;");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings = new RuleEngine(List.of(new R03BlockingFuture())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R03"));
    }

    @Test
    void doesNotFireWithoutRegionThreadAnnotation() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR03", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", false);
        Bytecode.invokeVirtual(mv, OWNER, "get", "()Ljava/lang/Object;");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings = new RuleEngine(List.of(new R03BlockingFuture())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isEmpty();
    }
}
