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

class R07RawDistanceManagerTicketTest {

    private static final String OWNER = "net/minecraft/server/level/DistanceManager";

    @Test
    void firesOnAddTicketOneArgOverload() {
        assertFires(bytecodeFor(
                "BadR07a", "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V"));
    }

    @Test
    void firesOnAddTicketWithUnitArgOverload() {
        assertFires(bytecodeFor(
                "BadR07b",
                "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V"));
    }

    @Test
    void firesOnAddTicketFromDeeplyNestedHelper() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR07c", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "grantSpawnChunks", false);
        Bytecode.invokeVirtual(mv, OWNER, "addTicket", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);
        assertFires(bytes);
    }

    @Test
    void doesNotFireOnServerChunkCacheAddRegionTicket() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR07a", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "grantSpawnChunks", false);
        Bytecode.invokeVirtual(mv, "net/minecraft/server/level/ServerChunkCache", "addRegionTicket", "()V");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireOnDistanceManagerRemoveTicket() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR07b", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "releaseSpawnChunks", false);
        Bytecode.invokeVirtual(mv, OWNER, "removeTicket", "()V");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireInsideMultiforgePackage() {
        ClassWriter cw = Bytecode.newClass("net/multiforge/runtime/chunk/DistanceManagerFacade", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "grantSpawnChunks", false);
        Bytecode.invokeVirtual(mv, OWNER, "addTicket", "()V");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    private static byte[] bytecodeFor(String simpleName, String desc) {
        ClassWriter cw = Bytecode.newClass("com/example/mod/" + simpleName, false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "grantSpawnChunks", false);
        Bytecode.invokeVirtual(mv, OWNER, "addTicket", desc);
        Bytecode.endVoid(mv);
        return Bytecode.finish(cw);
    }

    private static void assertFires(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R07RawDistanceManagerTicket())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R07"));
    }

    private static void assertDoesNotFire(byte[] bytes) {
        List<Finding> findings =
                new RuleEngine(List.of(new R07RawDistanceManagerTicket())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isEmpty();
    }
}
