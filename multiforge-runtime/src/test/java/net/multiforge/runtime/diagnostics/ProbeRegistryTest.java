/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
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
