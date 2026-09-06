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

    /**
     * Scans {@code jarOrDir} exactly as {@link #scan(File)}, then partitions the results against
     * {@code ignoreFile} per {@code docs/design/scanner-rules.md} &sect;5.4:
     *
     * <ul>
     *   <li>{@link IgnoreFile.MatchResult#SUPPRESSED} findings are dropped from {@link
     *       ScanOutcome#reported()} and counted in {@link ScanOutcome#suppressedCount()}.
     *   <li>{@link IgnoreFile.MatchResult#STALE} findings stay in {@link ScanOutcome#reported()}
     *       (a stale suppression is not silently un-suppressed away, nor silently suppressed) and
     *       are additionally listed in {@link ScanOutcome#staleSuppressions()}.
     *   <li>{@link IgnoreFile.MatchResult#NOT_MATCHED} findings stay in {@link
     *       ScanOutcome#reported()} unchanged.
     * </ul>
     *
     * {@link ScanOutcome#all()} always carries every computed finding, suppressed or not — report
     * summary counts (doc §6.1's {@code errors}/{@code warnings}) are computed from the full set,
     * matching the existing {@link Main} JSON summary behavior.
     */
    public ScanOutcome scan(File jarOrDir, IgnoreFile ignoreFile) throws IOException {
        List<Finding> all = scan(jarOrDir);
        List<Finding> reported = new ArrayList<>();
        List<Finding> stale = new ArrayList<>();
        int suppressed = 0;
        for (Finding f : all) {
            IgnoreFile.MatchResult result = ignoreFile.match(f);
            switch (result) {
                case SUPPRESSED -> suppressed++;
                case STALE -> {
                    stale.add(f);
                    reported.add(f);
                }
                case NOT_MATCHED -> reported.add(f);
            }
        }
        return new ScanOutcome(all, List.copyOf(reported), suppressed, List.copyOf(stale));
    }

    /**
     * Result of {@link #scan(File, IgnoreFile)}.
     *
     * @param all every computed finding, suppressed or not
     * @param reported {@code all} minus suppressed findings (stale suppressions still included)
     * @param suppressedCount count of findings matched and suppressed by the ignore file
     * @param staleSuppressions findings whose rule-id/class/method matched an ignore-file entry
     *     but whose line-hash did not (doc §5.4's "visible stale suppression note")
     */
    public record ScanOutcome(
            List<Finding> all, List<Finding> reported, int suppressedCount, List<Finding> staleSuppressions) {}

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
        List<byte[]> classEntries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                classEntries.add(readAllBytes(zis));
            }
        }

        // First pass (round-6 fork C HIGH finding, R03): index every class's direct
        // superclass/interfaces before running any rule, so a rule can resolve whether an
        // instruction's owner is a mod-defined subtype of some target type — not just a
        // literal match against the type itself. Cheap: SKIP_CODE, one ClassReader.accept
        // per class, same cost class as the existing ClassContext extraction pass.
        TypeHierarchy hierarchy = buildTypeHierarchy(classEntries);

        List<Finding> findings = new ArrayList<>();
        for (byte[] bytes : classEntries) {
            try {
                findings.addAll(scanClassBytes(bytes, jar.getName(), hierarchy));
            } catch (RuntimeException e) {
                // One unparseable class entry never aborts the whole-jar scan (doc §2).
            }
        }
        return findings;
    }

    private static TypeHierarchy buildTypeHierarchy(List<byte[]> classEntries) {
        List<TypeHierarchy.ClassInfo> infos = new ArrayList<>();
        for (byte[] bytes : classEntries) {
            try {
                infos.add(readHeader(new ClassReader(bytes)).toTypeInfo());
            } catch (RuntimeException e) {
                // A class unparseable for hierarchy purposes is also unparseable for rule
                // scanning below, where it's already tolerated the same way (doc §2).
            }
        }
        return TypeHierarchy.index(infos);
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    /**
     * Scans a single class's raw bytecode against the configured rules, with no jar-wide {@link
     * TypeHierarchy} available (equivalent to {@link TypeHierarchy#EMPTY} — resolves pure-JDK
     * hierarchies via classpath reflection, not mod-defined subtypes). Public and independent of
     * jar-walking so tests can build fixture classes in memory (via {@code ClassWriter}) without a
     * jar on disk.
     */
    public List<Finding> scanClassBytes(byte[] classBytes, String sourceJarName) {
        return scanClassBytes(classBytes, sourceJarName, TypeHierarchy.EMPTY);
    }

    /**
     * Scans a single class's raw bytecode against the configured rules, with {@code hierarchy}
     * (built by {@link #scanJar} from every class in the same jar) available to rules that need
     * to resolve polymorphic receivers.
     */
    public List<Finding> scanClassBytes(byte[] classBytes, String sourceJarName, TypeHierarchy hierarchy) {
        ClassReader reader = new ClassReader(classBytes);
        ClassContext ctx = extractContext(reader, sourceJarName, hierarchy);
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            try {
                rule.visit(ctx, reader, findings::add);
            } catch (RuntimeException e) {
                // A single misbehaving rule must never take the other 11 down with it for this
                // class, and must never abort the whole-jar scan (doc §2 / CLAUDE.md rule 5
                // spirit: reroute + warn, never refuse). AbstractTreeRule already guards its own
                // reader.accept(...), but this catch is the last line of defense for any rule
                // that throws from its own pattern-matching logic instead.
            }
        }
        return findings;
    }

    private static ClassContext extractContext(ClassReader reader, String sourceJarName, TypeHierarchy hierarchy) {
        Header header = readHeader(reader);
        boolean isModEntry = header.annotations.contains(ClassContext.MOD_ANNOTATION_DESC);
        return new ClassContext(
                header.className,
                header.superName,
                List.copyOf(header.interfaces),
                Set.copyOf(header.annotations),
                sourceJarName,
                isModEntry,
                hierarchy);
    }

    /** Class-file header info shared by {@link #extractContext} and the {@link TypeHierarchy} first pass. */
    private static Header readHeader(ClassReader reader) {
        ContextVisitor visitor = new ContextVisitor();
        reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Header(
                visitor.className, visitor.superName, List.copyOf(visitor.interfaces), Set.copyOf(visitor.annotations));
    }

    private record Header(String className, String superName, List<String> interfaces, Set<String> annotations) {
        TypeHierarchy.ClassInfo toTypeInfo() {
            return new TypeHierarchy.ClassInfo(className, superName, interfaces);
        }
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
