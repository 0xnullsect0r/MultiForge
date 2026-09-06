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

class R09SyncIoInTickTest {

    @Test
    void firesOnFilesReadAllBytesInRegionThreadMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR09a", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeStatic(mv, "java/nio/file/Files", "readAllBytes", "()[B");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnFileInputStreamReadInTickEventHandler() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR09b", false);
        MethodVisitor mv =
                Bytecode.beginEventHandler(cw, "onServerTick", "net/neoforged/neoforge/event/tick/ServerTickEvent");
        Bytecode.invokeVirtual(mv, "java/io/FileInputStream", "read", "()I");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnRandomAccessFileReadFullyInRegionThreadMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR09c", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeVirtual(mv, "java/io/RandomAccessFile", "readFully", "()V");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnFileChannelReadInRegionThreadMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR09d", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeInterface(mv, "java/nio/channels/FileChannel", "read", "()I");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnFilesLinesInRegionThreadMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR09e", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeStatic(mv, "java/nio/file/Files", "lines", "()Ljava/util/stream/Stream;");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnFilesReadStringTwoArgOverloadInRegionThreadMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR09f", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeStatic(
                mv,
                "java/nio/file/Files",
                "readString",
                "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/lang/String;");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnBufferedInputStreamReadWrappingAFileInputStream() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR09g", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeVirtual(mv, "java/io/BufferedInputStream", "read", "()I");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnRandomAccessFileWriteInRegionThreadMethod() {
        // Not read-prefixed — exercises the "RandomAccessFile.*" wildcard, not the read* prefix.
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR09h", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeVirtual(mv, "java/io/RandomAccessFile", "seek", "(J)V");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void firesOnInputStreamTransferToInRegionThreadMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR09i", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeVirtual(mv, "java/io/InputStream", "transferTo", "(Ljava/io/OutputStream;)J");
        Bytecode.endVoid(mv);
        assertFires(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireWhenNotTickReachable() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR09a", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", false);
        Bytecode.invokeStatic(mv, "java/nio/file/Files", "readAllBytes", "()[B");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireOnFilesWriteInRegionThreadMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR09b", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeStatic(mv, "java/nio/file/Files", "write", "()Ljava/nio/file/Path;");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    @Test
    void doesNotFireOnUnrelatedReadNamedMethodOnDifferentOwner() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR09c", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onChunkLoad", true);
        Bytecode.invokeVirtual(mv, "com/example/mod/ConfigParser", "readValue", "()Ljava/lang/Object;");
        Bytecode.endVoid(mv);
        assertDoesNotFire(Bytecode.finish(cw));
    }

    private static void assertFires(byte[] bytes) {
        List<Finding> findings = new RuleEngine(List.of(new R09SyncIoInTick())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isNotEmpty();
        assertThat(findings).allMatch(f -> f.ruleId().equals("R09"));
    }

    private static void assertDoesNotFire(byte[] bytes) {
        List<Finding> findings = new RuleEngine(List.of(new R09SyncIoInTick())).scanClassBytes(bytes, "test.jar");
        assertThat(findings).isEmpty();
    }
}
