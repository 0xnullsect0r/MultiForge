/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

/**
 * A single rule hit. See {@code docs/design/scanner-rules.md} &sect;2.1.
 *
 * @param ruleId "R01".."R12"
 * @param severity WARN or ERROR, fixed per rule
 * @param className dotted fully-qualified class name, e.g. {@code com.example.mod.FooBlock}
 * @param methodName {@code "<method>(<descriptor>)"}, or {@code "<field:name>"} for field-only
 *     findings (currently only R04), or {@code "<class-init>"} for class-level findings
 * @param line best-effort source line; {@code -1} if no line-number table entry covers the site
 * @param message human-readable, one sentence, includes the offending call/field
 * @param fingerprint see {@code docs/design/scanner-rules.md} &sect;5 — used for
 *     {@code .multiforgeignore} matching
 */
public record Finding(
        String ruleId,
        Severity severity,
        String className,
        String methodName,
        int line,
        String message,
        String fingerprint) {}
