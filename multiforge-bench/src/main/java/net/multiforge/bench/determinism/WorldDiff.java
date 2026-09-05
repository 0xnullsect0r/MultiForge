/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.bench.determinism;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.TreeMap;

/**
 * File-level SHA-256 comparison of two world directories, tolerating a
 * small allowlist of file names known to carry wall-clock or session
 * metadata that legitimately differ between runs of the same seed.
 *
 * <p>See {@link DeterminismHarness} for the M7 scope note on why
 * byte-identical comparison is legitimate here.
 */
public final class WorldDiff {

    /**
     * File names known to contain wall-clock timestamps, session ids, or
     * other run-to-run metadata that are expected to differ between two
     * runs of the same fixed seed and tick count. Matched by exact
     * base file name (not full path).
     */
    static final Set<String> NON_DETERMINISTIC_NAMES =
            Set.of("session.lock", "session.lock.old", "raids.dat", "raids.dat_old", "stats", "advancements");

    /**
     * Suffixes on top of the exact-name allowlist above. Vanilla writes
     * {@code .dat_old} copies of every save file every autosave — these
     * are stale byte-for-byte snapshots from the *previous* tick and
     * differ across identical-seed reruns depending on when the harness
     * captured the world dir. Matches per /67 review finding #13.
     */
    static final Set<String> NON_DETERMINISTIC_SUFFIXES = Set.of(".dat_old");

    private WorldDiff() {}

    public static Result compare(Path baselineRoot, Path patchedRoot) {
        TreeMap<String, String> baselineHashes = hashTree(baselineRoot);
        TreeMap<String, String> patchedHashes = hashTree(patchedRoot);
        return new Result(baselineRoot, patchedRoot, baselineHashes, patchedHashes);
    }

    private static TreeMap<String, String> hashTree(Path root) {
        TreeMap<String, String> out = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if (NON_DETERMINISTIC_NAMES.contains(name)) {
                        return FileVisitResult.CONTINUE;
                    }
                    for (String suffix : NON_DETERMINISTIC_SUFFIXES) {
                        if (name.endsWith(suffix)) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    String rel = root.relativize(file).toString();
                    out.put(rel, hashFile(file, name));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (NON_DETERMINISTIC_NAMES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(file));
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * File dispatcher: {@code .mca} region files get canonically hashed
     * per-chunk-slot rather than as raw bytes, so identical-seed reruns
     * that write the same chunks in a different order (and therefore land
     * at different sector offsets in the location table) still compare
     * equal. Every other file is hashed verbatim.
     *
     * <p>Anvil (MCA) format:
     * <ul>
     *   <li>bytes 0–4095: location table, 1024 big-endian entries of
     *       {@code (sectorOffset << 8) | sectorCount}; all-zero = slot
     *       empty. Non-deterministic across reruns when chunks defrag
     *       or grow past their prior sector count.
     *   <li>bytes 4096–8191: timestamp table, 1024 big-endian
     *       {@code SecondsSinceEpoch} entries. Wall-clock-derived, so
     *       always differs across reruns.
     *   <li>bytes 8192+: chunk payload data at sector-aligned offsets;
     *       each starts with a big-endian 32-bit length + 1 compression
     *       byte + compressed payload.
     * </ul>
     *
     * <p>We iterate 1024 chunk slots in fixed order; for each occupied
     * slot we hash {@code (slotIndex, payloadBytes)} where
     * {@code payloadBytes} is the length-prefixed chunk body read from
     * its own declared length (not the sector-count in the location
     * table). Empty slots and malformed entries are silently skipped —
     * an empty slot in one run vs. an occupied slot in the other still
     * causes a hash mismatch through the slotIndex prefix, so real
     * chunk-loss regressions are caught.
     */
    static String hashFile(Path file, String name) {
        if (!name.endsWith(".mca")) return sha256(file);
        try {
            byte[] bytes = Files.readAllBytes(file);
            return canonicalMcaHash(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String canonicalMcaHash(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Files smaller than the location table can't be a valid MCA — hash as-is
            // so a truncated file still produces a stable but distinctive hash.
            if (bytes.length < 4096) {
                md.update(bytes);
                return toHex(md.digest());
            }
            for (int slot = 0; slot < 1024; slot++) {
                int loc = readBigEndianInt(bytes, slot * 4);
                if (loc == 0) {
                    // Truly empty slot — the "no data present" sentinel below is what
                    // distinguishes this from a MALFORMED-but-nonzero slot.
                    md.update(SLOT_EMPTY_SENTINEL);
                    continue;
                }
                // long arithmetic throughout so a 24-bit sectorOffset × 4096 = up to 36 bits
                // does NOT wrap to a negative int and defeat the bounds checks. /67 round-3
                // finding: prior signed-int arithmetic crashed the whole harness on any
                // adversarial/corrupt input (single bad .mca killed the diff run).
                int sectorOffset = loc >>> 8; // 24 bits
                int sectorCount = loc & 0xFF; // 8 bits, 0..255
                long payloadStartL = (long) sectorOffset * 4096L;
                if (sectorOffset < 2 || sectorCount == 0 || payloadStartL + 5L > (long) bytes.length) {
                    // Nonzero-but-malformed slot: hash the raw location entry so run A's
                    // "slot 5 = 0x00000001" is DISTINGUISHABLE from run B's "slot 5 = 0".
                    // Prior code fell through to the same `continue` as empty → silent
                    // MATCH on corruption regressions.
                    hashMalformedSlot(md, slot, loc);
                    continue;
                }
                int payloadStart = (int) payloadStartL; // safe: bounded by bytes.length < Integer.MAX_VALUE
                int chunkLength = readBigEndianInt(bytes, payloadStart); // includes the 1 compression byte
                // Guard the second overflow site: `payloadStart + 4 + chunkLength` also wraps
                // to a negative int for chunkLength ≥ ~2 GiB. Same crash pattern.
                if (chunkLength <= 0 || (long) chunkLength > (long) bytes.length - (long) payloadStart - 4L) {
                    hashMalformedSlot(md, slot, loc);
                    continue;
                }
                // Feed slot index (fixed order) + payload bytes (length-prefixed).
                md.update(slotIndexBytes(slot));
                md.update(bytes, payloadStart, 4 + chunkLength);
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static final byte[] SLOT_EMPTY_SENTINEL = {(byte) 0xE0, (byte) 0x5E, 0, 0}; // "àSE  "

    private static final byte[] SLOT_MALFORMED_SENTINEL = {(byte) 0xBA, (byte) 0xDF, (byte) 0x00, (byte) 0x0D};

    private static byte[] slotIndexBytes(int slot) {
        return new byte[] {(byte) (slot >>> 24), (byte) (slot >>> 16), (byte) (slot >>> 8), (byte) slot};
    }

    private static void hashMalformedSlot(MessageDigest md, int slot, int loc) {
        md.update(SLOT_MALFORMED_SENTINEL);
        md.update(slotIndexBytes(slot));
        // Include the raw loc entry so different corrupt values distinguish from each other.
        md.update(new byte[] {(byte) (loc >>> 24), (byte) (loc >>> 16), (byte) (loc >>> 8), (byte) loc});
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Result of comparing two world directories.
     *
     * @param onlyInBaseline files present in the baseline but not in the patched world
     * @param onlyInPatched files present in the patched world but not in the baseline
     * @param mismatched files present in both but whose contents differ (rel path → baseline hash / patched hash)
     */
    public record Result(
            Path baselineRoot,
            Path patchedRoot,
            java.util.NavigableMap<String, String> baselineHashes,
            java.util.NavigableMap<String, String> patchedHashes) {

        public boolean matches() {
            return onlyInBaseline().isEmpty()
                    && onlyInPatched().isEmpty()
                    && mismatched().isEmpty();
        }

        public Set<String> onlyInBaseline() {
            TreeMap<String, String> a = new TreeMap<>(baselineHashes);
            a.keySet().removeAll(patchedHashes.keySet());
            return a.keySet();
        }

        public Set<String> onlyInPatched() {
            TreeMap<String, String> a = new TreeMap<>(patchedHashes);
            a.keySet().removeAll(baselineHashes.keySet());
            return a.keySet();
        }

        public java.util.NavigableMap<String, String[]> mismatched() {
            TreeMap<String, String[]> out = new TreeMap<>();
            for (var e : baselineHashes.entrySet()) {
                String rel = e.getKey();
                String bh = e.getValue();
                String ph = patchedHashes.get(rel);
                if (ph != null && !ph.equals(bh)) {
                    out.put(rel, new String[] {bh, ph});
                }
            }
            return out;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("DeterminismHarness: comparing ")
                    .append(baselineRoot)
                    .append(" ↔ ")
                    .append(patchedRoot)
                    .append('\n');
            sb.append("  baseline files: ").append(baselineHashes.size()).append('\n');
            sb.append("  patched  files: ").append(patchedHashes.size()).append('\n');
            if (matches()) {
                sb.append("  RESULT: MATCH (byte-identical, ignoring known-nondeterministic paths)\n");
            } else {
                sb.append("  RESULT: MISMATCH\n");
                Set<String> only1 = onlyInBaseline();
                if (!only1.isEmpty()) {
                    sb.append("    only in baseline (").append(only1.size()).append("):\n");
                    only1.stream()
                            .limit(20)
                            .forEach(p -> sb.append("      ").append(p).append('\n'));
                    if (only1.size() > 20)
                        sb.append("      ... (").append(only1.size() - 20).append(" more)\n");
                }
                Set<String> only2 = onlyInPatched();
                if (!only2.isEmpty()) {
                    sb.append("    only in patched (").append(only2.size()).append("):\n");
                    only2.stream()
                            .limit(20)
                            .forEach(p -> sb.append("      ").append(p).append('\n'));
                    if (only2.size() > 20)
                        sb.append("      ... (").append(only2.size() - 20).append(" more)\n");
                }
                var mm = mismatched();
                if (!mm.isEmpty()) {
                    sb.append("    content differs (").append(mm.size()).append("):\n");
                    mm.entrySet().stream().limit(20).forEach(e -> sb.append("      ")
                            .append(e.getKey())
                            .append("  baseline=")
                            .append(e.getValue()[0], 0, 12)
                            .append("  patched=")
                            .append(e.getValue()[1], 0, 12)
                            .append('\n'));
                    if (mm.size() > 20)
                        sb.append("      ... (").append(mm.size() - 20).append(" more)\n");
                }
            }
            return sb.toString();
        }
    }
}
