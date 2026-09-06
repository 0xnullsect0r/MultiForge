/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Drives the configured {@link Rule}s over one or more mod jars (or
 * directories of jars). See {@code docs/design/scanner-rules.md} &sect;1.2.
 */
public final class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Scans {@code jarOrDir}: a single {@code .jar} file, or a directory walked recursively for
     * {@code *.jar} files (per the public CLI contract — loose {@code .class} trees are not a
     * supported input here; use {@link #scanClassBytes} directly for that).
     */
    public List<Finding> scan(File jarOrDir) throws IOException {
        List<Finding> findings = new ArrayList<>();
        for (File jar : collectJars(jarOrDir)) {
            findings.addAll(scanJar(jar));
        }
        return findings;
    }

    private static List<File> collectJars(File jarOrDir) throws IOException {
        if (jarOrDir.isFile()) {
            return List.of(jarOrDir);
        }
        if (!jarOrDir.isDirectory()) {
            throw new IOException("Not a file or directory: " + jarOrDir);
        }
        try (Stream<java.nio.file.Path> walk = Files.walk(jarOrDir.toPath())) {
            return walk.filter(p -> p.toString().endsWith(".jar"))
                    .map(java.nio.file.Path::toFile)
                    .toList();
        }
    }

    private List<Finding> scanJar(File jar) throws IOException {
        List<Finding> findings = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes = readAllBytes(zis);
                try {
                    findings.addAll(scanClassBytes(bytes, jar.getName()));
                } catch (RuntimeException e) {
                    // One unparseable class entry never aborts the whole-jar scan (doc §2).
                }
            }
        }
        return findings;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    /**
     * Scans a single class's raw bytecode against the configured rules. Public and independent of
     * jar-walking so tests can build fixture classes in memory (via {@code ClassWriter}) without a
     * jar on disk.
     */
    public List<Finding> scanClassBytes(byte[] classBytes, String sourceJarName) {
        ClassReader reader = new ClassReader(classBytes);
        ClassContext ctx = extractContext(reader, sourceJarName);
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            rule.visit(ctx, reader, findings::add);
        }
        return findings;
    }

    private static ClassContext extractContext(ClassReader reader, String sourceJarName) {
        ContextVisitor visitor = new ContextVisitor();
        reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        boolean isModEntry = visitor.annotations.contains(ClassContext.MOD_ANNOTATION_DESC);
        return new ClassContext(
                visitor.className,
                visitor.superName,
                List.copyOf(visitor.interfaces),
                Set.copyOf(visitor.annotations),
                sourceJarName,
                isModEntry);
    }

    private static final class ContextVisitor extends ClassVisitor {
        String className;
        String superName;
        final List<String> interfaces = new ArrayList<>();
        final Set<String> annotations = new HashSet<>();

        ContextVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;
            this.superName = superName;
            if (interfaces != null) {
                this.interfaces.addAll(List.of(interfaces));
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations.add(descriptor);
            return null;
        }
    }
}
