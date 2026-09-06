/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.RuleEngine;
import net.multiforge.scanner.testsupport.Bytecode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class R03BlockingFutureTest {

    private static final String OWNER = "java/util/concurrent/CompletableFuture";

    @TempDir
    Path tempDir;

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
    void firesOnJoinAfterOrTimeoutChain() {
        // future.orTimeout(...).join() — orTimeout itself doesn't block and isn't flagged, but
        // the trailing .join() is its own INVOKEVIRTUAL against the still-CompletableFuture
        // result and fires exactly like a bare .join() would.
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR03OrTimeout", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", true);
        Bytecode.invokeVirtual(mv, OWNER, "orTimeout", "(JLjava/util/concurrent/TimeUnit;)L" + OWNER + ";");
        Bytecode.invokeVirtual(mv, OWNER, "join", "()Ljava/lang/Object;");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings = new RuleEngine(List.of(new R03BlockingFuture())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).ruleId()).isEqualTo("R03");
    }

    @Test
    void firesOnGetNow() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR03GetNow", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", true);
        Bytecode.invokeVirtual(mv, OWNER, "getNow", "(Ljava/lang/Object;)Ljava/lang/Object;");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings = new RuleEngine(List.of(new R03BlockingFuture())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
    }

    @Test
    void firesOnAwaitUninterruptiblyOnAFutureTypedReceiver() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR03Await", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", true);
        Bytecode.invokeInterface(mv, "java/util/concurrent/Future", "awaitUninterruptibly", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings = new RuleEngine(List.of(new R03BlockingFuture())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
    }

    @Test
    void firesOnForkJoinTaskJoin() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR03ForkJoin", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", true);
        Bytecode.invokeVirtual(mv, "java/util/concurrent/ForkJoinTask", "join", "()Ljava/lang/Object;");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings = new RuleEngine(List.of(new R03BlockingFuture())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
    }

    @Test
    void firesOnCompletionStageTypedReceiver() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR03Stage", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", true);
        Bytecode.invokeInterface(mv, "java/util/concurrent/CompletionStage", "get", "()Ljava/lang/Object;");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings = new RuleEngine(List.of(new R03BlockingFuture())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isNotEmpty();
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

    @Test
    void doesNotFireOnAnUnrelatedTypeWithASimilarlyNamedMethod() {
        ClassWriter cw = Bytecode.newClass("com/example/mod/GoodR03Unrelated", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", true);
        Bytecode.invokeVirtual(mv, "com/example/mod/PartyRoster", "join", "()V");
        Bytecode.endVoid(mv);
        byte[] bytes = Bytecode.finish(cw);

        List<Finding> findings = new RuleEngine(List.of(new R03BlockingFuture())).scanClassBytes(bytes, "test.jar");

        assertThat(findings).isEmpty();
    }

    /**
     * Round-6 fork C HIGH finding: a mod-defined subtype of {@code CompletableFuture} must still
     * be caught. Since {@code scanClassBytes(byte[], String)} scans one class in isolation with
     * no jar-wide {@link net.multiforge.scanner.TypeHierarchy}, this needs a real two-class jar
     * scanned through {@link RuleEngine#scan(java.io.File)} so the first pass can index {@code
     * MyFuture}'s {@code extends CompletableFuture} edge before R03 runs.
     */
    @Test
    void firesOnJoinCalledThroughAModDefinedCompletableFutureSubtype() throws IOException {
        byte[] myFutureClass = buildMyFutureSubtypeClass();
        byte[] callerClass = buildRegionThreadCallerJoiningOn("com/example/mod/MyFuture");

        Path jar = tempDir.resolve("poly-future.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(jos, "com/example/mod/MyFuture.class", myFutureClass);
            writeEntry(jos, "com/example/mod/BadR03Poly.class", callerClass);
        }

        List<Finding> findings = new RuleEngine(List.of(new R03BlockingFuture())).scan(jar.toFile());

        assertThat(findings).hasSize(1);
        Finding finding = findings.get(0);
        assertThat(finding.ruleId()).isEqualTo("R03");
        assertThat(finding.className()).isEqualTo("com.example.mod.BadR03Poly");
    }

    /** A mod-defined {@code class MyFuture extends CompletableFuture<Object>} with only the default constructor. */
    private static byte[] buildMyFutureSubtypeClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/mod/MyFuture", null, OWNER, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OWNER, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return Bytecode.finish(cw);
    }

    /** A {@code @RegionThread} method calling {@code .join()} with the given internal name as the bytecode owner. */
    private static byte[] buildRegionThreadCallerJoiningOn(String receiverInternalName) {
        ClassWriter cw = Bytecode.newClass("com/example/mod/BadR03Poly", false);
        MethodVisitor mv = Bytecode.beginMethod(cw, "onNeighborChanged", true);
        Bytecode.invokeVirtual(mv, receiverInternalName, "join", "()Ljava/lang/Object;");
        Bytecode.endVoid(mv);
        return Bytecode.finish(cw);
    }

    private static void writeEntry(JarOutputStream jos, String name, byte[] bytes) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(bytes);
        jos.closeEntry();
    }
}
