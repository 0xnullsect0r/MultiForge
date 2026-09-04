/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.mod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModIdentifierTest {

    @Test
    void acceptsCanonicalModIds() {
        assertThat(ModIdentifier.of("multiforge").modId()).isEqualTo("multiforge");
        assertThat(ModIdentifier.of("create_abc-def").modId()).isEqualTo("create_abc-def");
    }

    @Test
    void rejectsUppercase() {
        assertThatThrownBy(() -> ModIdentifier.of("MultiForge")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> ModIdentifier.of("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLeadingDigit() {
        assertThatThrownBy(() -> ModIdentifier.of("1mod")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversize() {
        assertThatThrownBy(() -> ModIdentifier.of("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }
}
