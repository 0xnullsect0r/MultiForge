/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Computes the {@code .multiforgeignore} fingerprint string for a finding.
 * See {@code docs/design/scanner-rules.md} &sect;5.2.
 *
 * <p>The line-hash is SHA-256 (first 12 hex chars) of the flagged
 * instruction plus its immediate &plusmn;2 instructions, restricted to
 * "real" instructions (pseudo-nodes like labels, line numbers, and frames
 * are skipped when building the window) — a stable, deterministic slice
 * that survives pure source-line moves but changes when the actual flagged
 * call site changes.
 */
public final class Fingerprint {

    private Fingerprint() {}

    /**
     * @param ruleId "R01".."R12"
     * @param classFqnDotted dotted fully-qualified class name
     * @param methodKey {@code "name(descriptor)"}, {@code "<field:name>"}, or {@code "<class-init>"}
     * @param mn the method containing the flagged instruction
     * @param flagged the flagged instruction, must be present in {@code mn.instructions}
     * @return {@code "<ruleId>:<classFqn>#<methodKey>#<hash>"}
     */
    public static String compute(
            String ruleId, String classFqnDotted, String methodKey, MethodNode mn, AbstractInsnNode flagged) {
        String hash = sha256Hex12(String.join("|", window(mn, flagged)));
        return ruleId + ":" + classFqnDotted + "#" + methodKey + "#" + hash;
    }

    /** Overload for field-only findings (R04) that have no single flagged instruction window beyond the PUTSTATIC site itself. */
    public static String compute(String ruleId, String classFqnDotted, String methodKey, List<String> window) {
        String hash = sha256Hex12(String.join("|", window));
        return ruleId + ":" + classFqnDotted + "#" + methodKey + "#" + hash;
    }

    /** Builds the &plusmn;2 real-instruction window around {@code flagged}, in order. */
    public static List<String> window(MethodNode mn, AbstractInsnNode flagged) {
        List<AbstractInsnNode> real = new ArrayList<>();
        int flaggedIndex = -1;
        for (AbstractInsnNode insn : mn.instructions) {
            if (!isRealInstruction(insn)) {
                continue;
            }
            if (insn == flagged) {
                flaggedIndex = real.size();
            }
            real.add(insn);
        }
        List<String> out = new ArrayList<>();
        if (flaggedIndex < 0) {
            // Flagged node wasn't a "real" instruction by our filter (shouldn't happen for the
            // call sites this scanner flags) — fall back to just describing it alone.
            out.add(describe(flagged));
            return out;
        }
        int from = Math.max(0, flaggedIndex - 2);
        int to = Math.min(real.size() - 1, flaggedIndex + 2);
        for (int i = from; i <= to; i++) {
            out.add(describe(real.get(i)));
        }
        return out;
    }

    private static boolean isRealInstruction(AbstractInsnNode insn) {
        return switch (insn.getType()) {
            case AbstractInsnNode.LABEL, AbstractInsnNode.LINE, AbstractInsnNode.FRAME -> false;
            default -> true;
        };
    }

    /**
     * Deterministic, dependency-free instruction descriptor (no {@code asm-util}
     * mnemonic table — that would pull in {@code org.ow2.asm:asm-util}, which
     * isn't in this module's dependency set).
     */
    static String describe(AbstractInsnNode insn) {
        StringBuilder sb = new StringBuilder();
        sb.append(insn.getType()).append(':').append(insn.getOpcode());
        if (insn instanceof MethodInsnNode m) {
            sb.append(':').append(m.owner).append('.').append(m.name).append(m.desc);
        } else if (insn instanceof FieldInsnNode f) {
            sb.append(':')
                    .append(f.owner)
                    .append('.')
                    .append(f.name)
                    .append(':')
                    .append(f.desc);
        } else if (insn instanceof TypeInsnNode t) {
            sb.append(':').append(t.desc);
        } else if (insn instanceof LdcInsnNode l) {
            sb.append(':').append(l.cst);
        } else if (insn instanceof VarInsnNode v) {
            sb.append(':').append(v.var);
        } else if (insn instanceof IntInsnNode ii) {
            sb.append(':').append(ii.operand);
        }
        return sb.toString();
    }

    private static String sha256Hex12(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (byte b : digest) {
                if (hex.length() >= 12) {
                    break;
                }
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK digest algorithm", e);
        }
    }
}
