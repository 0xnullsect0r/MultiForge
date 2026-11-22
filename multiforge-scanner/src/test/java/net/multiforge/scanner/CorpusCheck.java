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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The scanner's gate on real mod jars: scan each jar of the corpus with every
 * active rule and compare the findings — one line per finding, {@code <jar>
 * <rule> <severity> <class>#<method>} — with the committed {@code
 * multiforge-scanner/corpus/expected.txt}. A rule change that adds, drops or
 * moves a finding on a known jar fails until the expectation is updated
 * deliberately ({@code ./gradlew :multiforge-scanner:scanCorpus
 * -PupdateCorpus}).
 *
 * <p>Arguments: the expectations file, {@code --update} optionally, {@code
 * --unported=<jar base>} for each corpus jar this release line cannot build
 * yet (its expected lines are kept but not compared, and the run says so),
 * then the jars. Jar names are reduced to their base (version stripped) so a version
 * bump does not churn the file.
 */
public final class CorpusCheck {
    public static void main(String[] args) throws Exception {
        Path expectedFile = Path.of(args[0]);
        boolean update = args.length > 1 && args[1].equals("--update");
        int firstJar = update ? 2 : 1;
        List<String> unported = new ArrayList<>();
        while (firstJar < args.length && args[firstJar].startsWith("--unported=")) {
            unported.add(args[firstJar++].substring("--unported=".length()));
        }
        if (update && !unported.isEmpty()) {
            throw new IllegalArgumentException("--update would drop the expected lines of " + unported);
        }
        RuleEngine engine = new RuleEngine(Main.ACTIVE_RULES);
        TreeSet<String> actual = new TreeSet<>();
        for (int i = firstJar; i < args.length; i++) {
            File jar = new File(args[i]);
            String base = jar.getName().replaceAll("-\\d[\\w.+-]*\\.jar$", "").replaceAll("\\.jar$", "");
            for (Finding f : engine.scan(jar)) {
                actual.add(base + " " + f.ruleId() + " " + f.severity() + " " + f.className() + "#" + f.methodName());
            }
            actual.add(base + " scanned");
        }
        if (update) {
            List<String> lines = new ArrayList<>();
            lines.add("# Expected scanner findings on the corpus (see CorpusCheck). Regenerate with");
            lines.add("# ./gradlew :multiforge-scanner:scanCorpus -PupdateCorpus after a deliberate rule change.");
            lines.addAll(actual);
            Files.write(expectedFile, lines, StandardCharsets.UTF_8);
            System.out.println("CorpusCheck: wrote " + actual.size() + " lines to " + expectedFile);
            return;
        }
        TreeSet<String> expected = new TreeSet<>();
        for (String line : Files.readAllLines(expectedFile, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String entry = line.strip();
            if (unported.stream().noneMatch(jar -> entry.startsWith(jar + " "))) expected.add(entry);
        }
        unported.forEach(
                jar -> System.out.println("CorpusCheck: NOT SCANNED — " + jar + " is not ported to this line"));
        TreeSet<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        TreeSet<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        if (missing.isEmpty() && extra.isEmpty()) {
            System.out.println("CorpusCheck: PASS — " + actual.size() + " expected lines");
            return;
        }
        missing.forEach(l -> System.err.println("  expected, not found: " + l));
        extra.forEach(l -> System.err.println("  found, not expected: " + l));
        System.err.println("CorpusCheck: FAIL — scanner output on the corpus changed");
        System.exit(1);
    }

    private CorpusCheck() {}
}
