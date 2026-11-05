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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
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
 *       {@code MinecraftServer.execute}, {@code ServerDomains} tasks) — and the
 *       same-class helpers such a body calls, which run on that thread too.
 * </ul>
 *
 * <p>The analysis does not follow calls into other classes, so a helper another class
 * calls from tick code is reported too. MultiForge reroutes an off-thread {@code setBlock}
 * to the server thread at runtime (CLAUDE.md rule 5), so this is a warning, not an error.
 */
public final class R02OffThreadLevelSetBlock extends AbstractTreeRule {

    private static final String OWNER = "net/minecraft/world/level/Level";
    private static final Set<String> TARGET_OWNERS = Set.of(OWNER);
    /**
     * Vanilla {@code Level} subclasses a receiver may be statically typed as. The scan's
     * in-jar hierarchy index cannot resolve these (the scanner is Minecraft-free), so they
     * are hand-listed; a mod-defined {@code Level} subclass resolves through the index
     * instead, and an unknown third-party subtype between them stays a documented gap.
     */
    private static final Set<String> VANILLA_LEVEL_SUBTYPES = Set.of(
            "net/minecraft/server/level/ServerLevel",
            "net/minecraft/client/multiplayer/ClientLevel",
            "net/minecraft/world/level/LevelAccessor");

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
        // Scheduler-handed lambda bodies are seeds, not just exemptions: a same-class helper
        // they call runs on the same thread and must not be reported either.
        var tickReachable = TickReachability.compute(cn, TickReachability.scheduledLambdaBodies(cn));
        for (MethodNode mn : cn.methods) {
            String methodKey = BytecodeUtil.methodKey(mn);
            if (tickReachable.contains(mn.name + mn.desc) || TickReachability.isGameCallback(mn)) {
                continue;
            }
            for (var insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !TARGET_NAMES.contains(call.name)
                        || !isLevelReceiver(call.owner, ctx)) {
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
     * The receiver of a {@code setBlock} call: {@code Level} itself, a hand-listed vanilla
     * subtype, or anything the scan's hierarchy index resolves to {@code Level} (a mod-defined
     * subclass — javac stamps the receiver's static type on the instruction, and mod code
     * usually holds a {@code ServerLevel}, not the base class).
     */
    private static boolean isLevelReceiver(String owner, ClassContext ctx) {
        return owner.equals(OWNER)
                || VANILLA_LEVEL_SUBTYPES.contains(owner)
                || ctx.typeHierarchy().isSubtypeOfAny(owner, TARGET_OWNERS);
    }
}
