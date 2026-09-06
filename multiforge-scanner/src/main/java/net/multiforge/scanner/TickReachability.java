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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Conservative "is this method reachable from the region-tick thread"
 * heuristic used by R02 and R04. See {@code docs/design/scanner-rules.md}
 * &sect;1.4.
 *
 * <p>A method is tick-reachable if:
 *
 * <ul>
 *   <li>it carries {@code @RegionThread}, or
 *   <li>it carries a NeoForge/Forge {@code @SubscribeEvent}-family annotation and its sole event
 *       parameter type name ends in {@code TickEvent}, or
 *   <li>it is reachable by a direct (non-virtual-dispatch-resolved) call chain of depth &le; 3
 *       from such a method, resolved within the same class file only.
 * </ul>
 *
 * This is explicitly a lint heuristic, not a soundness proof — see doc
 * &sect;1.4 for the accepted gaps (virtual dispatch, reflection, cross-class
 * closure).
 */
public final class TickReachability {

    /** Internal descriptor for {@code net.multiforge.api.RegionThread}. */
    public static final String REGION_THREAD_DESC = "Lnet/multiforge/api/RegionThread;";

    private static final int MAX_DEPTH = 3;

    private TickReachability() {}

    /** Returns the set of {@code name+descriptor} keys of tick-reachable methods in {@code cn}. */
    public static Set<String> compute(ClassNode cn) {
        List<MethodNode> methods = cn.methods;
        Map<String, MethodNode> byKey = new HashMap<>();
        for (MethodNode m : methods) {
            byKey.put(key(m), m);
        }

        Set<String> reachable = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        Map<String, Integer> depthOf = new HashMap<>();

        for (MethodNode m : methods) {
            if (isSeed(m)) {
                String k = key(m);
                if (reachable.add(k)) {
                    depthOf.put(k, 0);
                    frontier.add(k);
                }
            }
        }

        while (!frontier.isEmpty()) {
            String currentKey = frontier.poll();
            int depth = depthOf.get(currentKey);
            if (depth >= MAX_DEPTH) {
                continue;
            }
            MethodNode current = byKey.get(currentKey);
            if (current == null || current.instructions == null) {
                continue;
            }
            for (var insn : current.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (!call.owner.equals(cn.name)) {
                    continue;
                }
                String calleeKey = call.name + call.desc;
                if (!byKey.containsKey(calleeKey)) {
                    continue;
                }
                if (reachable.add(calleeKey)) {
                    depthOf.put(calleeKey, depth + 1);
                    frontier.add(calleeKey);
                }
            }
        }

        return reachable;
    }

    private static boolean isSeed(MethodNode m) {
        if (hasAnnotation(m, TickReachability.REGION_THREAD_DESC)) {
            return true;
        }
        if (!hasSubscribeEventAnnotation(m)) {
            return false;
        }
        Type[] args = Type.getArgumentTypes(m.desc);
        for (Type arg : args) {
            if (arg.getSort() == Type.OBJECT && arg.getInternalName().endsWith("TickEvent")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSubscribeEventAnnotation(MethodNode m) {
        return hasAnnotationSuffix(m, "SubscribeEvent;");
    }

    private static boolean hasAnnotation(MethodNode m, String descriptor) {
        return annotationDescriptors(m).stream().anyMatch(d -> d.equals(descriptor));
    }

    private static boolean hasAnnotationSuffix(MethodNode m, String suffix) {
        return annotationDescriptors(m).stream().anyMatch(d -> d.endsWith(suffix));
    }

    private static List<String> annotationDescriptors(MethodNode m) {
        List<String> descs = new ArrayList<>();
        addAll(descs, m.visibleAnnotations);
        addAll(descs, m.invisibleAnnotations);
        return descs;
    }

    private static void addAll(List<String> out, List<AnnotationNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (AnnotationNode n : nodes) {
            out.add(n.desc);
        }
    }

    private static String key(MethodNode m) {
        return m.name + m.desc;
    }
}
