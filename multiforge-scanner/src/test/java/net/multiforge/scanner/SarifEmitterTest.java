/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.multiforge.scanner.rules.R01DirectChunkMapInvoke;
import net.multiforge.scanner.rules.R02OffThreadLevelSetBlock;
import net.multiforge.scanner.rules.R03BlockingFuture;
import net.multiforge.scanner.rules.R04UnsyncStaticMutation;
import net.multiforge.scanner.rules.R05EntitySetPosOffCoord;
import net.multiforge.scanner.rules.R06DirectServerChunkCacheMutation;
import net.multiforge.scanner.rules.R07RawDistanceManagerTicket;
import net.multiforge.scanner.rules.R08OffThreadBlockEntitySetChanged;
import net.multiforge.scanner.rules.R09SyncIoInTick;
import net.multiforge.scanner.rules.R10ThreadStartInModCtor;
import net.multiforge.scanner.rules.R11ReflectOnNeoforgedInternal;
import net.multiforge.scanner.rules.R12CaptureServerInLambda;
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

    private static final List<Rule> ALL_RULES = List.of(
            new R01DirectChunkMapInvoke(),
            new R02OffThreadLevelSetBlock(),
            new R03BlockingFuture(),
            new R04UnsyncStaticMutation(),
            new R05EntitySetPosOffCoord(),
            new R06DirectServerChunkCacheMutation(),
            new R07RawDistanceManagerTicket(),
            new R08OffThreadBlockEntitySetChanged(),
            new R09SyncIoInTick(),
            new R10ThreadStartInModCtor(),
            new R11ReflectOnNeoforgedInternal(),
            new R12CaptureServerInLambda());

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
}
