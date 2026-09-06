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
package net.multiforge.runtime.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OwnerTokenTest {

    @Test
    void currentIsUnknownOnPlainThread() {
        assertThat(OwnerToken.current().domain()).isEqualTo(Domain.UNKNOWN);
    }

    @Test
    void runAsAppliesAndRestores() {
        OwnerToken before = OwnerToken.current();
        OwnerToken.runAs(OwnerToken.forRegion(42L), () -> {
            OwnerToken now = OwnerToken.current();
            assertThat(now.domain()).isEqualTo(Domain.REGION);
            assertThat(now.regionId()).isEqualTo(42L);
        });
        assertThat(OwnerToken.current().domain()).isEqualTo(before.domain());
    }

    @Test
    void nestedRunAsRestoresProperly() {
        OwnerToken.runAs(OwnerToken.GLOBAL, () -> {
            assertThat(OwnerToken.current().domain()).isEqualTo(Domain.GLOBAL);
            OwnerToken.runAs(OwnerToken.forRegion(7L), () -> {
                assertThat(OwnerToken.current().regionId()).isEqualTo(7L);
            });
            assertThat(OwnerToken.current().domain()).isEqualTo(Domain.GLOBAL);
        });
        assertThat(OwnerToken.current().domain()).isEqualTo(Domain.UNKNOWN);
    }
}
