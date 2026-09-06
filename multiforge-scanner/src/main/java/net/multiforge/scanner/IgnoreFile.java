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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Parses and matches {@code .multiforgeignore} fingerprint-suppression files. See {@code
 * docs/design/scanner-rules.md} &sect;5.
 *
 * <p>Line format (&sect;5.2): {@code <rule-id>:<class-fqn>#<method>#<line-hash>} — exactly {@link
 * Finding#fingerprint()}'s own format, so a suppression line is literally "paste the fingerprint
 * from a report you want to accept." Lines starting with {@code #} and blank lines are ignored.
 *
 * <p>Matching (&sect;5.4) is deliberately not a simple string-equality check against the full
 * fingerprint: a finding whose {@code rule-id#class-fqn#method} triple matches an ignore-file
 * entry but whose line-hash does not is a <b>stale suppression</b>, not a silent pass-through —
 * the caller ({@link RuleEngine#scan(java.io.File, IgnoreFile)}) surfaces that distinction rather
 * than collapsing it into "suppressed" or "not suppressed."
 *
 * <p>Per &sect;5.1, multiple files layer (a global CLI-working-directory file plus a per-input
 * sibling file both apply) — {@link #merge(IgnoreFile)} implements that layering; no file
 * "replaces" another, matches only ever add up.
 */
public final class IgnoreFile {

    /** Empty ignore file — every {@link #match} call returns {@link MatchResult#NOT_MATCHED}. */
    public static final IgnoreFile EMPTY = new IgnoreFile(Map.of());

    /** {@code rule-id class-fqn method -> set of accepted line-hashes}. */
    private final Map<String, Set<String>> hashesByKey;

    private IgnoreFile(Map<String, Set<String>> hashesByKey) {
        this.hashesByKey = hashesByKey;
    }

    /** Result of matching one {@link Finding} against this ignore file. */
    public enum MatchResult {
        /** A line matches rule-id + class-fqn + method + line-hash exactly — drop the finding from the report. */
        SUPPRESSED,
        /** A line matches rule-id + class-fqn + method but the line-hash differs — surface as a stale-suppression note; the finding itself is still reported. */
        STALE,
        /** No line matches rule-id + class-fqn + method at all. */
        NOT_MATCHED
    }

    /**
     * Parses {@code file}. Returns {@link #EMPTY} (not an error) if {@code file} does not exist —
     * "no {@code .multiforgeignore} at this location" is the overwhelmingly common case, not a
     * failure.
     */
    public static IgnoreFile load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return EMPTY;
        }
        Map<String, Set<String>> parsed = new HashMap<>();
        for (String rawLine : Files.readAllLines(file)) {
            ParsedLine line = parseLine(rawLine);
            if (line == null) {
                continue;
            }
            parsed.computeIfAbsent(line.key(), k -> new HashSet<>()).add(line.hash());
        }
        return new IgnoreFile(parsed);
    }

    /** Combines this ignore file's entries with {@code other}'s — union, never replacement (doc §5.1). */
    public IgnoreFile merge(IgnoreFile other) {
        if (other == null || other.hashesByKey.isEmpty()) {
            return this;
        }
        if (this.hashesByKey.isEmpty()) {
            return other;
        }
        Map<String, Set<String>> combined = new HashMap<>();
        for (var e : this.hashesByKey.entrySet()) {
            combined.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        for (var e : other.hashesByKey.entrySet()) {
            combined.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
        }
        return new IgnoreFile(combined);
    }

    /** See {@link MatchResult}. */
    public MatchResult match(Finding finding) {
        Set<String> hashes = hashesByKey.get(key(finding.ruleId(), finding.className(), finding.methodName()));
        if (hashes == null) {
            return MatchResult.NOT_MATCHED;
        }
        return hashes.contains(hashTailOf(finding.fingerprint())) ? MatchResult.SUPPRESSED : MatchResult.STALE;
    }

    /** True iff this ignore file has no entries at all. */
    public boolean isEmpty() {
        return hashesByKey.isEmpty();
    }

    private static String hashTailOf(String fingerprint) {
        return fingerprint.substring(fingerprint.lastIndexOf('#') + 1);
    }

    private static String key(String ruleId, String classFqn, String method) {
        return ruleId + ' ' + classFqn + ' ' + method;
    }

    private record ParsedLine(String ruleId, String classFqn, String method, String hash) {
        String key() {
            return IgnoreFile.key(ruleId, classFqn, method);
        }
    }

    /**
     * Splits one raw line into its four fingerprint components, or returns {@code null} for a
     * comment, a blank line, or a line that doesn't match the {@code
     * <rule-id>:<class-fqn>#<method>#<hash>} shape (malformed lines are skipped, never a scanner
     * crash — same "never refuse" spirit as the rule visitors).
     */
    private static ParsedLine parseLine(String rawLine) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        int firstHash = line.indexOf('#');
        int lastHash = line.lastIndexOf('#');
        if (firstHash < 0 || firstHash == lastHash) {
            return null;
        }
        String ruleAndClass = line.substring(0, firstHash);
        String method = line.substring(firstHash + 1, lastHash);
        String hash = line.substring(lastHash + 1);
        int colon = ruleAndClass.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String ruleId = ruleAndClass.substring(0, colon);
        String classFqn = ruleAndClass.substring(colon + 1);
        if (ruleId.isEmpty() || classFqn.isEmpty() || method.isEmpty() || hash.isEmpty()) {
            return null;
        }
        return new ParsedLine(ruleId, classFqn, method, hash);
    }
}
