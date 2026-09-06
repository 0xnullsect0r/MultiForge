/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Catalog-level invariants for the full 12-rule set (doc §8's "12/12 rules" exit-gate framing,
 * applied to rule metadata/registration rather than per-fixture firing — the firing half of that
 * invariant lives in each {@code RxxRuleTest}, which asserts its rule fires on 3 independent bad
 * shapes and stays silent on 3 near-miss good shapes).
 */
class RuleCatalogTest {

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

    /** Fixed per doc §4 — the frozen severity table. */
    private static final List<Severity> EXPECTED_SEVERITY_BY_INDEX = List.of(
            Severity.WARN, // R01
            Severity.ERROR, // R02
            Severity.ERROR, // R03
            Severity.WARN, // R04
            Severity.ERROR, // R05
            Severity.WARN, // R06
            Severity.WARN, // R07
            Severity.WARN, // R08
            Severity.ERROR, // R09
            Severity.WARN, // R10
            Severity.WARN, // R11
            Severity.ERROR // R12
            );

    @Test
    void exactlyTwelveRulesAreRegistered() {
        assertThat(ALL_RULES).hasSize(12);
    }

    @Test
    void ruleIdsAreExactlyR01ThroughR12WithNoDuplicates() {
        List<String> ids = ALL_RULES.stream().map(Rule::id).toList();
        List<String> expected = Stream.iterate(1, n -> n + 1)
                .limit(12)
                .map(n -> String.format("R%02d", n))
                .toList();

        assertThat(ids).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void everyRuleHasNonBlankNameAndDescription() {
        assertThat(ALL_RULES).allSatisfy(rule -> {
            assertThat(rule.name()).as(rule.id() + ".name()").isNotBlank();
            assertThat(rule.description()).as(rule.id() + ".description()").isNotBlank();
        });
    }

    @ParameterizedTest
    @MethodSource("ruleAndExpectedSeverity")
    void severityMatchesTheFrozenDocTable(Rule rule, Severity expected) {
        assertThat(rule.severity()).as(rule.id()).isEqualTo(expected);
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> ruleAndExpectedSeverity() {
        return Stream.iterate(0, i -> i + 1)
                .limit(12)
                .map(i -> org.junit.jupiter.params.provider.Arguments.of(
                        ALL_RULES.get(i), EXPECTED_SEVERITY_BY_INDEX.get(i)));
    }
}
