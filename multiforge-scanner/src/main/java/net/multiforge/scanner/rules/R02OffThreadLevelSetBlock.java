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
package net.multiforge.scanner.rules;

import java.util.Set;
import java.util.function.Consumer;
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import net.multiforge.scanner.TickReachability;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R02 — {@code off-thread-Level.setBlock}. See {@code docs/design/scanner-rules.md} &sect;4 (R02).
 *
 * <p>WARN: a call to {@code Level.setBlock} (or an overload) from a method that is not known to
 * run on a region or the server thread. Known to run there:
 *
 * <ul>
 *   <li>tick-reachable methods ({@link TickReachability}), lambda bodies included;
 *   <li>Vanilla game callbacks a block, item or entity overrides ({@code onPlace},
 *       {@code neighborChanged}, {@code useItemOn}, {@code tick}, …): they take the
 *       level and run on the region owning the position;
 *   <li>a lambda or method reference passed straight to a scheduler that runs it on
 *       the server thread or a region ({@code IPayloadContext.enqueueWork},
 *       {@code MinecraftServer.execute}, {@code ServerDomains} tasks).
 * </ul>
 *
 * <p>The analysis does not follow calls into other classes, so a helper another class
 * calls from tick code is reported too. MultiForge reroutes an off-thread {@code setBlock}
 * to the server thread at runtime (CLAUDE.md rule 5), so this is a warning, not an error.
 */
public final class R02OffThreadLevelSetBlock extends AbstractTreeRule {

    private static final String OWNER = "net/minecraft/world/level/Level";
    private static final Set<String> TARGET_NAMES = Set.of("setBlock");

    @Override
    public String id() {
        return "R02";
    }

    @Override
    public String name() {
        return "off-thread-Level.setBlock";
    }

    @Override
    public String description() {
        return "Level.setBlock called from a method not reachable on the region-tick thread.";
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        String classFqn = ctx.className().replace('/', '.');
        var tickReachable = TickReachability.compute(cn);
        Set<String> scheduled = scheduledLambdaBodies(cn);
        for (MethodNode mn : cn.methods) {
            String methodKey = BytecodeUtil.methodKey(mn);
            if (tickReachable.contains(mn.name + mn.desc)
                    || scheduled.contains(mn.name + mn.desc)
                    || isGameCallback(mn)) {
                continue;
            }
            for (var insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals(OWNER)
                        || !TARGET_NAMES.contains(call.name)) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        "Level." + call.name + call.desc + " called from " + mn.name
                                + ", which is not known to run on a region or the server thread. If it can run"
                                + " on another thread (a network or executor thread), schedule it with"
                                + " ServerDomains.region(level, pos) or IPayloadContext.enqueueWork; MultiForge"
                                + " reroutes it otherwise.",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }

    /**
     * Vanilla callbacks that take the level and run on the region owning the position (or the
     * server thread): a block's, item's or entity's override of one of them is not off-thread.
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

    private static boolean isGameCallback(MethodNode mn) {
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
     */
    private static Set<String> scheduledLambdaBodies(ClassNode cn) {
        Set<String> out = new java.util.HashSet<>();
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) continue;
            for (AbstractInsnNode insn : mn.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                Handle impl = TickReachability.lambdaImpl(indy);
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
}
