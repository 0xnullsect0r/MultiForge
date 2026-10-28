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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Asserts {@link ReportEmitter#toSarif} produces the required SARIF 2.1.0 top-level shape (doc
 * &sect;6.2) — no separate {@code SarifEmitter} class exists (see {@code ReportEmitter}'s
 * javadoc); this test targets that class's {@code toSarif} method directly. A full JSON-Schema
 * validation against the OASIS SARIF 2.1.0 schema would need a JSON-Schema library this module
 * deliberately doesn't depend on (doc §1.1's "no new dependency" gate), so this checks the
 * required top-level keys and shapes by hand instead.
 */
class SarifEmitterTest {

    // The list the scanner ships with, so a rule added or dropped there shows up here.
    private static final List<Rule> ALL_RULES = Main.ACTIVE_RULES;

    private static final Finding SAMPLE_ERROR = new Finding(
            "R03",
            Severity.ERROR,
            "com.example.mod.ChunkListener",
            "onNeighborChanged(Lnet/minecraft/core/BlockPos;)V",
            42,
            "CompletableFuture.get() called from @RegionThread method onNeighborChanged",
            "R03:com.example.mod.ChunkListener#onNeighborChanged(Lnet/minecraft/core/BlockPos;)V#a1b2c3d4e5f6");

    private static final Finding SAMPLE_WARN = new Finding(
            "R01",
            Severity.WARN,
            "com.example.mod.FooBlock",
            "poke()V",
            10,
            "Direct call to ChunkMap.getVisibleChunkIfPresent bypasses the facade",
            "R01:com.example.mod.FooBlock#poke()V#0123456789ab");

    @Test
    void carriesRequiredTopLevelSchemaAndVersionKeys() {
        String sarif = ReportEmitter.toSarif(ALL_RULES, List.of());

        assertThat(sarif).contains("\"$schema\"");
        assertThat(sarif).contains("sarif-schema-2.1.0.json");
        assertThat(sarif).contains("\"version\": \"2.1.0\"");
        assertThat(sarif).contains("\"runs\"");
        assertThat(sarif).contains("\"tool\"");
        assertThat(sarif).contains("\"driver\"");
    }

    @Test
    void driverRulesListsAllTwelveRuleIdsWithMetadata() {
        String sarif = ReportEmitter.toSarif(ALL_RULES, List.of());

        for (int n = 1; n <= 12; n++) {
            String id = String.format("R%02d", n);
            assertThat(sarif).as("driver.rules[] should list " + id).contains("\"id\": \"" + id + "\"");
        }
        assertThat(sarif).contains("\"shortDescription\"");
        assertThat(sarif).contains("\"defaultConfiguration\"");
    }

    @Test
    void mapsErrorSeverityToErrorLevel() {
        String sarif = ReportEmitter.toSarif(ALL_RULES, List.of(SAMPLE_ERROR));

        assertThat(sarif).contains("\"ruleId\": \"R03\"");
        assertThat(sarif).contains("\"level\": \"error\"");
    }

    @Test
    void mapsWarnSeverityToWarningLevel() {
        String sarif = ReportEmitter.toSarif(ALL_RULES, List.of(SAMPLE_WARN));

        assertThat(sarif).contains("\"ruleId\": \"R01\"");
        assertThat(sarif).contains("\"level\": \"warning\"");
    }

    @Test
    void resultCarriesPartialFingerprintFromLineHash() {
        String sarif = ReportEmitter.toSarif(ALL_RULES, List.of(SAMPLE_ERROR));

        assertThat(sarif).contains("\"multiforgeFingerprint/v1\": \"a1b2c3d4e5f6\"");
    }

    @Test
    void resultLocationUsesClassAsAJarRelativePath() {
        String sarif = ReportEmitter.toSarif(ALL_RULES, List.of(SAMPLE_ERROR));

        assertThat(sarif).contains("\"uri\": \"com/example/mod/ChunkListener.class\"");
        assertThat(sarif).contains("\"startLine\": 42");
    }

    @Test
    void aFindingWithoutALineHasNoRegion() {
        // SARIF requires startLine >= 1; a class compiled without line numbers has none.
        Finding noLine = new Finding(
                "R03",
                Severity.ERROR,
                "com.example.mod.NoDebug",
                "run()V",
                0,
                "msg",
                "R03:com.example.mod.NoDebug#run()V#000000000000");
        String sarif = ReportEmitter.toSarif(ALL_RULES, List.of(noLine));

        assertThat(sarif).contains("\"uri\": \"com/example/mod/NoDebug.class\"");
        assertThat(sarif).doesNotContain("\"startLine\"").doesNotContain("\"region\"");
    }
}
