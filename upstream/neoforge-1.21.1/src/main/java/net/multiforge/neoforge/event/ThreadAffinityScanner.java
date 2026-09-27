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
package net.multiforge.neoforge.event;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import net.multiforge.runtime.event.ListenerAffinity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Reads a listener's bytecode for {@link ListenerAffinity}: whether the
 * listener method — and the methods of its own class it calls, lambdas
 * included, up to {@value #DEPTH} calls deep — asks which thread it runs on
 * ({@code isSameThread}, {@code getRunningThread}) or writes a static
 * field outside a class initialiser.
 *
 * <p>It reads the class file the class loader serves, before mixins apply;
 * code in other classes is not followed. A listener given as a lambda or
 * method reference has no known method: every method of its host class that
 * takes the event type is read.
 */
public final class ThreadAffinityScanner implements ListenerAffinity.Scanner {
    static final int DEPTH = 3;

    @Override
    public ListenerAffinity.Scan scan(Class<?> host, Class<?> eventType, String methodName) {
        ClassNode node = read(host);
        if (node == null) return ListenerAffinity.Scan.UNREADABLE;
        return scan(node, Type.getInternalName(eventType), methodName);
    }

    static ListenerAffinity.Scan scan(ClassNode node, String eventInternalName, String methodName) {
        String eventDesc = "L" + eventInternalName + ";";
        Deque<MethodNode> todo = new ArrayDeque<>();
        Deque<Integer> depth = new ArrayDeque<>();
        Set<MethodNode> seen = new HashSet<>();
        for (MethodNode m : node.methods) {
            boolean start = methodName != null
                    ? m.name.equals(methodName)
                    : m.desc.contains(eventDesc) && !m.name.equals("<init>") && !m.name.equals("<clinit>");
            if (start && seen.add(m)) {
                todo.add(m);
                depth.add(0);
            }
        }
        if (todo.isEmpty()) return ListenerAffinity.Scan.UNREADABLE;
        boolean asksThread = false;
        boolean writesStatic = false;
        while (!todo.isEmpty()) {
            MethodNode m = todo.poll();
            int d = depth.poll();
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    if (asksThread(call)) asksThread = true;
                    if (call.owner.equals(node.name) && d < DEPTH) {
                        enqueue(node, call.name, call.desc, todo, depth, seen, d + 1);
                    }
                } else if (insn instanceof FieldInsnNode field && insn.getOpcode() == Opcodes.PUTSTATIC) {
                    writesStatic = true;
                } else if (insn instanceof InvokeDynamicInsnNode indy && d < DEPTH) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle h && h.getOwner().equals(node.name)) {
                            enqueue(node, h.getName(), h.getDesc(), todo, depth, seen, d + 1);
                        }
                    }
                }
            }
        }
        return new ListenerAffinity.Scan(true, asksThread, writesStatic);
    }

    private static boolean asksThread(MethodInsnNode call) {
        // Thread.currentThread alone is common and harmless (thread-locals, class
        // loaders); comparing it with the server thread needs getRunningThread.
        return call.name.equals("isSameThread") || call.name.equals("getRunningThread");
    }

    private static void enqueue(
            ClassNode node,
            String name,
            String desc,
            Deque<MethodNode> todo,
            Deque<Integer> depth,
            Set<MethodNode> seen,
            int d) {
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc) && !m.name.equals("<clinit>") && seen.add(m)) {
                todo.add(m);
                depth.add(d);
            }
        }
    }

    private static ClassNode read(Class<?> host) {
        ClassLoader loader = host.getClassLoader();
        String resource = host.getName().replace('.', '/') + ".class";
        try (InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(resource) : loader.getResourceAsStream(resource)) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
