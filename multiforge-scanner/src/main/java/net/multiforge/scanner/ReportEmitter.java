/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.scanner;

import java.time.Instant;
import java.util.List;

/**
 * Hand-rolled JSON and SARIF emitters — see {@code docs/design/scanner-rules.md} &sect;6.
 *
 * <p>Deliberately not using Jackson: despite doc §6's mention of {@code jackson-databind} as "already
 * a transitive dep elsewhere," nothing in this build actually depends on it today, and this
 * track's dependency list is scoped to {@code asm}/{@code asm-tree} only (see CLAUDE.md's
 * "adding a new dependency" gate). The report shapes are small and fixed, so a tiny
 * hand-written writer is simpler than justifying a new dependency for this slice.
 *
 * <p>{@code .multiforgeignore} suppression (C2.16) is not wired up yet, so {@code suppressed} and
 * {@code staleSuppressions} are always {@code 0} / empty for now.
 */
final class ReportEmitter {

    private static final String SCANNER_VERSION = "1.0.0";
    private static final String ASM_VERSION = "9.7";

    private ReportEmitter() {}

    static String toJson(List<String> inputs, List<Finding> allFindings, List<Finding> reported) {
        long errors =
                allFindings.stream().filter(f -> f.severity() == Severity.ERROR).count();
        long warnings =
                allFindings.stream().filter(f -> f.severity() == Severity.WARN).count();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"scannerVersion\": ").append(q(SCANNER_VERSION)).append(",\n");
        sb.append("  \"asmVersion\": ").append(q(ASM_VERSION)).append(",\n");
        sb.append("  \"scannedAt\": ").append(q(Instant.now().toString())).append(",\n");
        sb.append("  \"inputs\": ").append(jsonArray(inputs)).append(",\n");
        sb.append("  \"summary\": {")
                .append(" \"errors\": ")
                .append(errors)
                .append(", \"warnings\": ")
                .append(warnings)
                .append(", \"suppressed\": 0, \"staleSuppressions\": 0 },\n");
        sb.append("  \"findings\": [\n");
        for (int i = 0; i < reported.size(); i++) {
            sb.append(findingJson(reported.get(i)));
            sb.append(i == reported.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  ],\n");
        sb.append("  \"staleSuppressions\": []\n");
        sb.append("}");
        return sb.toString();
    }

    private static String findingJson(Finding f) {
        return "    {\n" + "      \"ruleId\": "
                + q(f.ruleId()) + ",\n" + "      \"severity\": "
                + q(f.severity().name()) + ",\n" + "      \"className\": "
                + q(f.className()) + ",\n" + "      \"method\": "
                + q(f.methodName()) + ",\n" + "      \"line\": "
                + f.line() + ",\n" + "      \"message\": "
                + q(f.message()) + ",\n" + "      \"fingerprint\": "
                + q(f.fingerprint()) + "\n" + "    }";
    }

    static String toSarif(List<Finding> reported) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(
                "  \"$schema\": \"https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json\",\n");
        sb.append("  \"version\": \"2.1.0\",\n");
        sb.append("  \"runs\": [\n");
        sb.append("    {\n");
        sb.append("      \"tool\": { \"driver\": { \"name\": \"multiforge-scanner\", \"version\": ")
                .append(q(SCANNER_VERSION))
                .append(" } },\n");
        sb.append("      \"results\": [\n");
        for (int i = 0; i < reported.size(); i++) {
            sb.append(resultSarif(reported.get(i)));
            sb.append(i == reported.size() - 1 ? "\n" : ",\n");
        }
        sb.append("      ]\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    private static String resultSarif(Finding f) {
        String level = f.severity() == Severity.ERROR ? "error" : "warning";
        String fingerprintTail = f.fingerprint().substring(f.fingerprint().lastIndexOf('#') + 1);
        return "        {\n" + "          \"ruleId\": "
                + q(f.ruleId()) + ",\n" + "          \"level\": "
                + q(level) + ",\n" + "          \"message\": { \"text\": "
                + q(f.message()) + " },\n"
                + "          \"locations\": [ { \"physicalLocation\": { \"artifactLocation\": { \"uri\": "
                + q(f.className().replace('.', '/') + ".class") + " }, \"region\": { \"startLine\": "
                + Math.max(f.line(), 0) + " } } } ],\n"
                + "          \"partialFingerprints\": { \"multiforgeFingerprint/v1\": "
                + q(fingerprintTail) + " }\n" + "        }";
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append(q(values.get(i)));
            if (i < values.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }
}
