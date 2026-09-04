/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProbeRegistryTest {

    @BeforeEach
    void reset() {
        ProbeRegistry.resetForTesting();
    }

    @Test
    void bumpAndGet() {
        ProbeRegistry.bump("a");
        ProbeRegistry.bump("a");
        ProbeRegistry.bump("b");
        assertThat(ProbeRegistry.get("a")).isEqualTo(2L);
        assertThat(ProbeRegistry.get("b")).isEqualTo(1L);
        assertThat(ProbeRegistry.get("c")).isEqualTo(0L);
    }

    @Test
    void snapshotIsSorted() {
        ProbeRegistry.bump("delta");
        ProbeRegistry.bump("alpha");
        ProbeRegistry.bump("charlie");
        var snap = ProbeRegistry.snapshot();
        assertThat(snap.keySet()).containsExactly("alpha", "charlie", "delta");
    }
}
