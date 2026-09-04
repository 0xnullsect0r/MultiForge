/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.multiforge.license.LicenseToken;
import net.multiforge.license.LicenseTokenParser;
import net.multiforge.license.internal.PublicKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoundTripTest {

    @Test
    void keygenThenSignThenVerify(@TempDir Path tmp) throws Exception {
        Main.main(new String[] {"keygen", "--out", tmp.resolve("test").toString()});

        Path privPath = tmp.resolve("test.priv");
        Path pubPath = tmp.resolve("test.pub");
        assertThat(Files.exists(privPath)).isTrue();
        assertThat(Files.exists(pubPath)).isTrue();

        String pubB64 = Files.readString(pubPath, StandardCharsets.UTF_8).trim();

        // Capture the signed token.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            Main.main(new String[] {
                "sign",
                "--key",
                privPath.toString(),
                "--kid",
                "unit-test",
                "--iss",
                "multiforge.example",
                "--sub",
                "cust_unit_test",
                "--feature",
                "core",
            });
        } finally {
            System.setOut(original);
        }
        String token = out.toString(StandardCharsets.UTF_8).trim();
        assertThat(token).contains(".");

        PublicKeys keys = PublicKeys.loadFrom("unit-test=" + pubB64 + "\n");
        LicenseTokenParser parser = new LicenseTokenParser(keys);
        LicenseToken parsed = parser.parse(token);
        assertThat(parsed.subject()).isEqualTo("cust_unit_test");
        assertThat(parsed.features()).containsExactly("core");
    }
}
