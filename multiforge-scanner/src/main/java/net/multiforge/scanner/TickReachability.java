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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
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
 *   <li>it carries {@code @RegionThread}, or its class does, or
 *   <li>its sole parameter is a tick event — a type whose name ends in {@code TickEvent}, or a
 *       class nested in one ({@code EntityTickEvent$Post}, {@code LevelTickEvent$Pre}: NeoForge
 *       21's tick events are all nested {@code Pre}/{@code Post} classes). The method need not
 *       carry {@code @SubscribeEvent}: handlers registered with {@code IEventBus.addListener(...)}
 *       have no annotation, and a method taking only a tick event is a tick handler either way; or
 *   <li>it is reachable by a direct (non-virtual-dispatch-resolved) call chain of depth &le; 3
 *       from such a method, resolved within the same class file only; a lambda or method
 *       reference created in a reachable method counts as a call to its body.
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
        return compute(cn, Set.of());
    }

    /**
     * {@link #compute(ClassNode)} plus {@code additionalSeeds}: {@code name+descriptor} keys of
     * methods already known to run on the region-tick or server thread by other means (R02's
     * scheduler-handed lambda bodies). Seeds still spread through the same intra-class call
     * chain, so a helper called from a seeded lambda counts too.
     */
    public static Set<String> compute(ClassNode cn, Set<String> additionalSeeds) {
        List<MethodNode> methods = cn.methods;
        Map<String, MethodNode> byKey = new HashMap<>();
        for (MethodNode m : methods) {
            byKey.put(key(m), m);
        }

        Set<String> reachable = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        Map<String, Integer> depthOf = new HashMap<>();

        boolean regionThreadClass = hasClassAnnotation(cn, REGION_THREAD_DESC);
        for (MethodNode m : methods) {
            if (regionThreadClass || isSeed(m)) {
                String k = key(m);
                if (reachable.add(k)) {
                    depthOf.put(k, 0);
                    frontier.add(k);
                }
            }
        }
        for (String seed : additionalSeeds) {
            if (byKey.containsKey(seed) && reachable.add(seed)) {
                depthOf.put(seed, 0);
                frontier.add(seed);
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
                String calleeKey;
                if (insn instanceof MethodInsnNode call) {
                    if (!call.owner.equals(cn.name)) continue;
                    calleeKey = call.name + call.desc;
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    // A lambda or method reference created here: its body runs where it
                    // is called, and a lambda made in tick code usually runs in it too
                    // (forEach, removeIf, a local Runnable).
                    Handle impl = lambdaImpl(indy);
                    if (impl == null || !impl.getOwner().equals(cn.name)) continue;
                    calleeKey = impl.getName() + impl.getDesc();
                } else {
                    continue;
                }
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

    /** The implementation method of a {@code LambdaMetafactory} call site, or {@code null}. */
    public static Handle lambdaImpl(InvokeDynamicInsnNode indy) {
        if (indy.bsm == null || !indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) return null;
        for (Object arg : indy.bsmArgs) {
            if (arg instanceof Handle h) return h;
        }
        return null;
    }

    /**
     * Vanilla callbacks that take the level (or reach it through {@code this}) and run on
     * the region owning the position, or the server thread: a block's, item's or entity's
     * override of one of them is not off-thread. Shared by the R02/R08 heuristic family.
     */
    private static final Set<String> GAME_CALLBACKS = Set.of(
            "onPlace",
            "onRemove",
            "neighborChanged",
            "updateShape",
            "tick",
            "randomTick",
            "animateTick",
            "useItemOn",
            "useWithoutItem",
            "use",
            "useOn",
            "entityInside",
            "stepOn",
            "fallOn",
            "playerWillDestroy",
            "playerDestroy",
            "setPlacedBy",
            "attack",
            "onProjectileHit",
            "onExplosionHit",
            "spawnAfterBreak",
            "inventoryTick",
            "onUseTick",
            "finishUsingItem",
            "releaseUsing",
            "aiStep",
            "baseTick",
            "die",
            "hurt",
            "interact",
            "mobInteract",
            "serverTick",
            "clientTick");

    /** Whether {@code mn} is shaped like one of {@link #GAME_CALLBACKS}. */
    public static boolean isGameCallback(MethodNode mn) {
        if (!GAME_CALLBACKS.contains(mn.name)) return false;
        for (Type t : Type.getArgumentTypes(mn.desc)) {
            if (t.getSort() != Type.OBJECT) continue;
            String n = t.getInternalName();
            if (n.equals("net/minecraft/world/level/Level")
                    || n.equals("net/minecraft/server/level/ServerLevel")
                    || n.equals("net/minecraft/world/level/LevelAccessor")) {
                return true;
            }
        }
        // Entity and item callbacks without a level parameter reach it through `this`.
        return mn.name.equals("aiStep") || mn.name.equals("baseTick") || mn.name.equals("tick");
    }

    /** Schedulers whose argument runs on the server thread or a region. */
    private static final Set<String> SCHEDULER_METHODS = Set.of(
            "enqueueWork", "execute", "executeBlocking", "submit", "tell", "run", "runDelayed", "runAtFixedRate");

    /**
     * Bodies of lambdas and method references handed straight to a scheduler: the next call
     * after the {@code invokedynamic} (arguments may sit in between) is one of {@link
     * #SCHEDULER_METHODS} on a Minecraft, NeoForge payload or MultiForge scheduler type.
     * Shared by the R02/R08 heuristic family (R02 seeds these into {@link #compute};
     * pass them as {@code additionalSeeds}).
     */
    public static Set<String> scheduledLambdaBodies(ClassNode cn) {
        Set<String> out = new HashSet<>();
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                Handle impl = lambdaImpl(indy);
                if (impl == null || !impl.getOwner().equals(cn.name)) continue;
                MethodInsnNode next = nextCall(indy);
                if (next != null && SCHEDULER_METHODS.contains(next.name) && isSchedulerOwner(next.owner)) {
                    out.add(impl.getName() + impl.getDesc());
                }
            }
        }
        return out;
    }

    private static MethodInsnNode nextCall(AbstractInsnNode from) {
        int budget = 8;
        for (AbstractInsnNode n = from.getNext(); n != null && budget-- > 0; n = n.getNext()) {
            if (n instanceof MethodInsnNode m) return m;
            if (n instanceof InvokeDynamicInsnNode) return null;
        }
        return null;
    }

    private static boolean isSchedulerOwner(String owner) {
        return owner.startsWith("net/minecraft/server/MinecraftServer")
                || owner.startsWith("net/minecraft/util/thread/")
                || owner.startsWith("net/neoforged/neoforge/network/handling/IPayloadContext")
                || owner.startsWith("net/multiforge/api/scheduler/");
    }

    private static boolean isSeed(MethodNode m) {
        if (hasAnnotation(m, TickReachability.REGION_THREAD_DESC)) {
            return true;
        }
        Type[] args = Type.getArgumentTypes(m.desc);
        return args.length == 1 && args[0].getSort() == Type.OBJECT && isTickEvent(args[0].getInternalName());
    }

    /** {@code .../XTickEvent} or a class nested in one ({@code .../XTickEvent$Post}). */
    static boolean isTickEvent(String internalName) {
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        int nested = simple.indexOf('$');
        String outer = nested < 0 ? simple : simple.substring(0, nested);
        return outer.endsWith("TickEvent") || simple.endsWith("TickEvent");
    }

    private static boolean hasAnnotation(MethodNode m, String descriptor) {
        return annotationDescriptors(m).stream().anyMatch(d -> d.equals(descriptor));
    }

    private static boolean hasClassAnnotation(ClassNode cn, String descriptor) {
        for (List<AnnotationNode> nodes : java.util.Arrays.asList(cn.visibleAnnotations, cn.invisibleAnnotations)) {
            if (nodes == null) continue;
            for (AnnotationNode n : nodes) if (n.desc.equals(descriptor)) return true;
        }
        return false;
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
