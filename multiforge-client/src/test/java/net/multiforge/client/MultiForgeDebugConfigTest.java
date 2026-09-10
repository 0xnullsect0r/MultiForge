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
package net.multiforge.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins every translation key {@link MultiForgeDebugConfig} declares
 * against {@code en_us.json}.
 *
 * <p>This exists because the first cut of the config screen shipped with
 * keys that matched nothing NeoForge looks up. {@code
 * ConfigurationScreen} falls back to {@code <modId>.configuration.<leaf
 * key>} when a value carries no explicit {@code translation(...)}, which
 * collided here — the boolean {@code overlays.hud} and the group {@code
 * hud} share the leaf name {@code hud}. Nothing failed at build or run
 * time; the screen just drew the wrong label. Every value now declares
 * an explicit key, and this makes a missing or renamed entry a build
 * failure rather than something you find in-game.
 *
 * <p>It reads the two files as text rather than loading the spec,
 * because the moddev NeoForge classpath is only on the {@code main}
 * compile classpath — a test cannot touch {@code ModConfigSpec}. Text
 * comparison is enough: the failure mode being guarded is a key in one
 * file with no counterpart in the other.
 */
class MultiForgeDebugConfigTest {

    /** `translation(LANG + "some.key")` in the config source. */
    private static final Pattern DECLARED = Pattern.compile("translation\\(LANG \\+ \"([^\"]+)\"\\)");

    /** A JSON object key, which is all we need from the lang file. */
    private static final Pattern LANG_KEY = Pattern.compile("\"([^\"]+)\"\\s*:");

    private static final String PREFIX = "multiforge_debug.configuration.";

    private static Set<String> langKeys() throws IOException {
        try (InputStream in =
                MultiForgeDebugConfigTest.class.getResourceAsStream("/assets/multiforge_debug/lang/en_us.json")) {
            assertThat(in).as("en_us.json on the test classpath").isNotNull();
            return matches(LANG_KEY, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Keys declared by the config spec, read from its source. Gradle runs
     * tests with the module directory as the working directory.
     */
    private static List<String> declaredKeys() throws IOException {
        Path src = Path.of("src/main/java/net/multiforge/client/MultiForgeDebugConfig.java");
        assertThat(src).as("config source, resolved from the module directory").exists();
        String java = Files.readString(src);
        List<String> keys = new ArrayList<>();
        Matcher m = DECLARED.matcher(java);
        while (m.find()) {
            keys.add(PREFIX + m.group(1));
        }
        return keys;
    }

    private static Set<String> matches(Pattern p, String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void theSpecDeclaresAKeyForEverySettingAndGroup() throws IOException {
        // 11 settings + 3 groups. A new setting without a translation()
        // call trips this, which is the point.
        assertThat(declaredKeys()).hasSize(14);
    }

    @Test
    void everyDeclaredKeyHasALabel() throws IOException {
        Set<String> lang = langKeys();
        for (String key : declaredKeys()) {
            assertThat(lang).as("label for %s", key).contains(key);
        }
    }

    @Test
    void everySettingHasATooltip() throws IOException {
        Set<String> lang = langKeys();
        for (String key : declaredKeys()) {
            if (key.contains(".group.")) continue; // groups render a button, not a tooltip row
            assertThat(lang).as("tooltip for %s", key).contains(key + ".tooltip");
        }
    }

    @Test
    void everyGroupHasAButtonLabel() throws IOException {
        Set<String> lang = langKeys();
        for (String key : declaredKeys()) {
            if (!key.contains(".group.")) continue;
            // ConfigurationScreen:906 renders `<key>.button` for a subsection.
            assertThat(lang).as("button for %s", key).contains(key + ".button");
        }
    }

    @Test
    void declaredKeysAreUnique() throws IOException {
        // The bug this test was written for: two settings resolving to
        // one key, so the screen shows one of them under the other's name.
        assertThat(declaredKeys()).doesNotHaveDuplicates();
    }

    @Test
    void theScreenTitleIsTranslated() throws IOException {
        // ConfigurationScreen:262 looks this one up directly.
        assertThat(langKeys()).contains(PREFIX + "title");
    }

    @Test
    void noStaleConfigurationKeysLingerInTheLangFile() throws IOException {
        Set<String> declared = new LinkedHashSet<>(declaredKeys());
        for (String key : langKeys()) {
            if (!key.startsWith(PREFIX)) continue;
            String base = key.endsWith(".tooltip")
                    ? key.substring(0, key.length() - ".tooltip".length())
                    : key.endsWith(".button") ? key.substring(0, key.length() - ".button".length()) : key;
            if (base.equals(PREFIX + "title") || base.startsWith(PREFIX + "section.")) {
                continue; // NeoForge looks these up by its own naming scheme
            }
            assertThat(declared)
                    .as("lang key %s has no matching translation() call", key)
                    .contains(base);
        }
    }
}
