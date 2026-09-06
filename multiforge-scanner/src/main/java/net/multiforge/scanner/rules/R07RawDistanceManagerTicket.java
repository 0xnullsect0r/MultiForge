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

import java.util.function.Consumer;
import net.multiforge.scanner.BytecodeUtil;
import net.multiforge.scanner.ClassContext;
import net.multiforge.scanner.Finding;
import net.multiforge.scanner.Fingerprint;
import net.multiforge.scanner.Severity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * R07 — {@code raw-DistanceManager-ticket}. See {@code docs/design/scanner-rules.md} &sect;4 (R07).
 *
 * <p>WARN: mod calls to {@code DistanceManager.addTicket} where the receiver resolves to {@code
 * net.minecraft.server.level.DistanceManager} directly, rather than through {@code
 * MultiForgeDistanceManager}.
 */
public final class R07RawDistanceManagerTicket extends AbstractTreeRule {

    private static final String OWNER = "net/minecraft/server/level/DistanceManager";
    private static final String TARGET_NAME = "addTicket";

    @Override
    public String id() {
        return "R07";
    }

    @Override
    public String name() {
        return "raw-DistanceManager-ticket";
    }

    @Override
    public String description() {
        return "Direct DistanceManager.addTicket call bypassing MultiForgeDistanceManager.";
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    protected void scanClass(ClassContext ctx, ClassNode cn, Consumer<Finding> emit) {
        if (ctx.className().startsWith("net/multiforge/") || ctx.className().startsWith("net/minecraft/")) {
            return;
        }
        String classFqn = ctx.className().replace('/', '.');
        for (MethodNode mn : cn.methods) {
            String methodKey = BytecodeUtil.methodKey(mn);
            for (var insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals(OWNER)
                        || !call.name.equals(TARGET_NAME)) {
                    continue;
                }
                emit.accept(new Finding(
                        id(),
                        severity(),
                        classFqn,
                        methodKey,
                        BytecodeUtil.lineOf(mn, call),
                        "DistanceManager.addTicket" + call.desc + " called directly from " + mn.name
                                + " — writes to the Vanilla ticket/tracker state MultiForge keeps per-region;"
                                + " use ServerChunkCache.addRegionTicket instead.",
                        Fingerprint.compute(id(), classFqn, methodKey, mn, call)));
            }
        }
    }
}
