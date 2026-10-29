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
import java.util.stream.Stream;
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

    // The list the scanner ships with, so a rule added or dropped there shows up here.
    private static final List<Rule> ALL_RULES = Main.ACTIVE_RULES;

    /** Fixed per doc §4 — the frozen severity table. */
    private static final List<Severity> EXPECTED_SEVERITY_BY_INDEX = List.of(
            Severity.WARN, // R01
            Severity.WARN, // R02
            Severity.ERROR, // R03
            Severity.WARN, // R04
            Severity.WARN, // R05
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
