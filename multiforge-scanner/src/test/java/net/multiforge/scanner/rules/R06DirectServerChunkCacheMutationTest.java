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

class R06DirectServerChunkCacheMutationTest {

    private static final String OWNER = "net/minecraft/server/level/ServerChunkCache";

    @Test
    void firesOnDirectAddRegionTicket() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR06", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "forceLoad", false);
        Bytecode.invokeVirtual(mv, OWNER, "addRegionTicket", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R06DirectServerChunkCacheMutation())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R06"));
    }

    @Test
    void doesNotFireOnGetterShapedCall() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR06", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "peek", false);
        Bytecode.invokeVirtual(mv, OWNER, "getChunkNow", "()Ljava/lang/Object;");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings =
                new RuleEngine(List.of(new R06DirectServerChunkCacheMutation())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isEmpty();
    }
}
