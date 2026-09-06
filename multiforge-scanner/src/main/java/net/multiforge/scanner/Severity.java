/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

/**
 * Finding severity. Fixed per-rule, never per-finding — see
 * {@code docs/design/scanner-rules.md} &sect;2.2.
 */
public enum Severity {
    WARN,
    ERROR
}
