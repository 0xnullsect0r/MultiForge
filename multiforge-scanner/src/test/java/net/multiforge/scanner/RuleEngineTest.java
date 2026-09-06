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
package net.multiforge.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.multiforge.scanner.rules.R01DirectChunkMapInvoke;
import net.multiforge.scanner.rules.R05EntitySetPosOffCoord;
import net.multiforge.scanner.testsupport.Bytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

class RuleEngineTest {

    @Test
    void dispatchesEveryConfiguredRuleOverOneClassRead() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/Multi", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "poke", false);
        Bytecode.invokeVirtual(mv, "net/minecraft/server/level/ChunkMap", "getVisibleChunkIfPresent", "()V");
        Bytecode.invokeVirtual(mv, "net/minecraft/world/entity/Entity", "setPos", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        RuleEngine engine = new RuleEngine(List.of(new R01DirectChunkMapInvoke(), new R05EntitySetPosOffCoord()));
        List<Finding> findings = engine.scanClassBytes(bytes, "test.jar");

        assertThat(findings).extracting(Finding::ruleId).contains("R01", "R05");
    }

    @Test
    void emptyClassProducesNoFindings() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/Empty", false);
        cw.visitEnd();
        // Bare class with just the default constructor Bytecode.newClass already adds.
        byte[] bytes = cw.toByteArray();

        RuleEngine engine = new RuleEngine(List.of(new R01DirectChunkMapInvoke(), new R05EntitySetPosOffCoord()));
        List<Finding> findings = engine.scanClassBytes(bytes, "test.jar");

        assertThat(findings).isEmpty();
    }

    @Test
    void malformedClassBytesRejectedAtClassReaderConstruction() {
        // ClassReader itself rejects malformed input at construction time (not a rule concern) —
        // graceful degradation for a bad entry is RuleEngine.scan(File)'s per-jar-entry
        // responsibility instead (see scanJar's catch around scanClassBytes).
        byte[] garbage = {0x00, 0x01, 0x02, 0x03};
        RuleEngine engine = new RuleEngine(List.of(new R01DirectChunkMapInvoke()));
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> engine.scanClassBytes(garbage, "test.jar"));
    }
}
