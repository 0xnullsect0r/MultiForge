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

class R01DirectChunkMapInvokeTest {

    private static final String OWNER = "net/minecraft/server/level/ChunkMap";

    @Test
    void firesOnDirectInternalChunkMapCall() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR01", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "poke", false);
        Bytecode.invokeVirtual(mv, OWNER, "getVisibleChunkIfPresent", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R01DirectChunkMapInvoke())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R01"));
    }

    @Test
    void doesNotFireOnAllowlistedStableCall() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR01", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "poke", false);
        Bytecode.invokeVirtual(mv, OWNER, "getServer", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R01DirectChunkMapInvoke())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isEmpty();
    }
}
