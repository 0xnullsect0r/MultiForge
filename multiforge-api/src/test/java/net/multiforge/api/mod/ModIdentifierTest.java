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
