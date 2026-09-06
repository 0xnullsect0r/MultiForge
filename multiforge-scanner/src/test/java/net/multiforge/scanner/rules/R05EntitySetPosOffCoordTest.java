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

class R05EntitySetPosOffCoordTest {

    private static final String OWNER = "net/minecraft/world/entity/Entity";

    @Test
    void firesOnDirectSetPosOutsideCoordinator() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR05", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "teleportToBase", false);
        Bytecode.invokeVirtual(mv, OWNER, "setPos", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R05EntitySetPosOffCoord())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R05"));
    }

    @Test
    void doesNotFireInsideEntityMigrationCoordinatorPackage() {
        ClassWriter cw = Bytecode.newClass("net/multiforge/runtime/entity/EntityMigrationCoordinator", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "completeAt", false);
        Bytecode.invokeVirtual(mv, OWNER, "setPos", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R05EntitySetPosOffCoord())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isEmpty();
    }
}
