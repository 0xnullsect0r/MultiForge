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
}
