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
                    if (NON_DETERMINISTIC_NAMES.contains(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = root.relativize(file).toString();
                    out.put(rel, sha256(file));
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
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
