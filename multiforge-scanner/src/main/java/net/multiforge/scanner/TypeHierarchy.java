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
package net.multiforge.scanner;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-scan class-hierarchy index used to resolve polymorphic receivers (round-6 fork C HIGH
 * finding against R03: a bytecode {@code INVOKEVIRTUAL}/{@code INVOKEINTERFACE} instruction's
 * owner is the call site's <em>static</em> receiver type, which for a mod-defined subtype (e.g.
 * {@code class MyFuture extends CompletableFuture<T>}) is the subtype itself, not the JDK
 * ancestor a literal owner-name check would look for).
 *
 * <p>{@link #index} is built by {@link RuleEngine} from a cheap first pass (header-only, {@code
 * SKIP_CODE}) over every class in the jar being scanned, recording each class's direct
 * superclass and interfaces. {@link #isSubtypeOfAny} then walks that in-jar index; whenever it
 * reaches a name the jar doesn't define (a JDK type, or a third-party dependency not shaded into
 * the jar), it falls back to real classpath reflection ({@link Class#forName(String, boolean,
 * ClassLoader)} with {@code initialize=false}, so no static initializer ever runs) — this is what
 * resolves, for example, {@code CompletableFuture}'s own {@code implements Future,
 * CompletionStage} without needing the JDK's hierarchy hand-modeled here. A name that resolves
 * through neither path (an external class not on this JVM's classpath) is treated as "no match"
 * rather than an error, matching the scanner's CLAUDE.md-rule-5-flavored "never crash the scan"
 * contract (see {@code AbstractTreeRule}).
 */
public final class TypeHierarchy {

    /** No in-jar index — every lookup falls straight through to classpath reflection. */
    public static final TypeHierarchy EMPTY = new TypeHierarchy(Map.of());

    private static final Map<String, Optional<Class<?>>> REFLECTION_CACHE = new ConcurrentHashMap<>();

    private final Map<String, ClassEdges> byInternalName;

    private TypeHierarchy(Map<String, ClassEdges> byInternalName) {
        this.byInternalName = byInternalName;
    }

    /** One class's direct hierarchy edges, as read from its class-file header. */
    public record ClassInfo(String internalName, String superInternalName, List<String> interfaceInternalNames) {}

    private record ClassEdges(String superInternalName, List<String> interfaceInternalNames) {}

    /** Builds an index from every class header seen in one scan pass. */
    public static TypeHierarchy index(List<ClassInfo> classes) {
        Map<String, ClassEdges> map = new HashMap<>();
        for (ClassInfo c : classes) {
            map.put(c.internalName(), new ClassEdges(c.superInternalName(), c.interfaceInternalNames()));
        }
        return new TypeHierarchy(Map.copyOf(map));
    }

    /**
     * True iff {@code internalName} is exactly one of {@code targets}, or transitively extends
     * or implements one of them — checked first against this scan's in-jar index, then against
     * the real JVM classpath for names the jar itself doesn't define.
     */
    public boolean isSubtypeOfAny(String internalName, Set<String> targets) {
        if (internalName == null) {
            return false;
        }
        return isSubtypeOfAny(internalName, targets, new HashSet<>());
    }

    private boolean isSubtypeOfAny(String internalName, Set<String> targets, Set<String> visited) {
        if (internalName == null || !visited.add(internalName)) {
            return false;
        }
        if (targets.contains(internalName)) {
            return true;
        }
        ClassEdges edges = byInternalName.get(internalName);
        if (edges != null) {
            if (isSubtypeOfAny(edges.superInternalName(), targets, visited)) {
                return true;
            }
            for (String itf : edges.interfaceInternalNames()) {
                if (isSubtypeOfAny(itf, targets, visited)) {
                    return true;
                }
            }
            return false;
        }
        // Not defined in this jar — fall back to a real classpath class if one resolves.
        return reflectiveSubtypeOfAny(internalName, targets);
    }

    private static boolean reflectiveSubtypeOfAny(String internalName, Set<String> targets) {
        Class<?> receiver = loadQuietly(internalName);
        if (receiver == null) {
            return false;
        }
        for (String target : targets) {
            Class<?> targetClass = loadQuietly(target);
            if (targetClass != null && targetClass.isAssignableFrom(receiver)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> loadQuietly(String internalName) {
        return REFLECTION_CACHE
                .computeIfAbsent(internalName, TypeHierarchy::forNameQuietly)
                .orElse(null);
    }

    private static Optional<Class<?>> forNameQuietly(String internalName) {
        try {
            return Optional.of(
                    Class.forName(internalName.replace('/', '.'), false, TypeHierarchy.class.getClassLoader()));
        } catch (Throwable notOnClasspath) {
            // Mod-internal or third-party type that's neither in the in-jar index nor on the
            // scanner's own classpath — "unknown, no match" rather than an error (see class
            // javadoc). Catches Throwable, not just ClassNotFoundException: LinkageError and
            // friends are just as much "can't resolve this" here.
            return Optional.empty();
        }
    }
}
