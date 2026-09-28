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
package net.multiforge.runtime.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EntityCensusTest {

    private static EntityCensus census(
            int known, int hidden, int accessible, int visible, int anv, int vna, int tnl, int lnt) {
        return new EntityCensus(
                "minecraft:overworld",
                known,
                hidden,
                accessible,
                visible,
                accessible,
                accessible - tnl + lnt,
                anv,
                vna,
                tnl,
                lnt,
                Map.of(
                        3L, Map.of("minecraft:squid", 5, "minecraft:cod", 2),
                        7L, Map.of("minecraft:squid", 1),
                        -1L, Map.of("minecraft:item", 4)),
                List.of(),
                0);
    }

    @Test
    void aHealthyLevelHasNoGaps() {
        EntityCensus c = census(12, 2, 10, 10, 0, 0, 0, 0);
        assertThat(c.gapKnownVisible()).isZero();
        assertThat(c.gapAccessibleVisible()).isZero();
        assertThat(c.gapTicking()).isZero();
        assertThat(c.healthy()).isTrue();
        assertThat(c.renderAudit()).last().asString().contains("no limbo entities");
    }

    @Test
    void limboEntitiesShowInTheGaps() {
        // 3 entities in accessible sections were never made visible; 2 ticking ones fell off the tick list.
        EntityCensus c = census(12, 2, 10, 7, 3, 0, 2, 0);
        assertThat(c.gapKnownVisible()).isEqualTo(3);
        assertThat(c.gapAccessibleVisible()).isEqualTo(3);
        assertThat(c.gapTicking()).isEqualTo(2);
        assertThat(c.mismatches()).isEqualTo(5);
        assertThat(c.healthy()).isFalse();
        assertThat(c.summaryLine())
                .contains("known=12", "visible=7", "known-visible=3", "accessible-visible=3", "ticking-ticklist=2");
    }

    @Test
    void countsAddUpByTypeAndRegion() {
        EntityCensus c = census(12, 0, 12, 12, 0, 0, 0, 0);
        assertThat(c.byType()).containsEntry("minecraft:squid", 6).containsEntry("minecraft:cod", 2);
        assertThat(c.perRegion()).containsEntry(3L, 7).containsEntry(7L, 1).containsEntry(-1L, 4);
        assertThat(c.render(1).get(1)).isEqualTo("  top types: minecraft:squid 6, …(2 more)");
        assertThat(c.render(1).get(2)).isEqualTo("  per region: region#3=7 …(2 more)");
        assertThat(c.renderRegion(3L, 10).get(1)).isEqualTo("  types: minecraft:squid 5, minecraft:cod 2");
        assertThat(c.renderRegion(99L, 10).get(0)).contains("no entities in region#99");
        assertThat(EntityCensus.regionName(-1L)).isEqualTo("no-region");
    }
}
