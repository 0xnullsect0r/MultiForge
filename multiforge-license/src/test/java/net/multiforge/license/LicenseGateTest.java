/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.multiforge.license.internal.Base64Url;
import net.multiforge.license.internal.Cbor;
import net.multiforge.license.internal.Ed25519;
import net.multiforge.license.internal.PublicKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseGateTest {

    private KeyPair keyPair;
    private PublicKeys publicKeys;
    private RecordingExiter runtime;

    @TempDir
    Path workingDir;

    @BeforeEach
    void setUp() {
        keyPair = Ed25519.generate(new SecureRandom());
        String rawB64 = Base64Url.encode(Ed25519.rawFromPublicKey(keyPair.getPublic()));
        publicKeys = PublicKeys.loadFrom("test-2026=" + rawB64 + "\n");
        runtime = new RecordingExiter();
    }

    @Test
    void bootstrapAcceptsTokenFromEnv() {
        String token = validToken();
        LicenseGate gate = new LicenseGate(publicKeys, workingDir, MapEnv.env(LicenseGate.ENV_VAR, token), runtime);

        LicenseToken result = gate.bootstrap();

        assertThat(result.subject()).isEqualTo("cust_test");
        assertThat(runtime.exitCode).isNull();
    }

    @Test
    void bootstrapAcceptsTokenFromSystemProperty() {
        String token = validToken();
        LicenseGate gate =
                new LicenseGate(publicKeys, workingDir, MapEnv.sys(LicenseGate.SYSTEM_PROPERTY, token), runtime);

        LicenseToken result = gate.bootstrap();

        assertThat(result.subject()).isEqualTo("cust_test");
        assertThat(runtime.exitCode).isNull();
    }

    @Test
    void bootstrapAcceptsTokenFromFile() throws Exception {
        String token = validToken();
        Files.writeString(workingDir.resolve(LicenseGate.FILE_NAME), token + "\n");
        LicenseGate gate = new LicenseGate(publicKeys, workingDir, MapEnv.empty(), runtime);

        LicenseToken result = gate.bootstrap();

        assertThat(result.subject()).isEqualTo("cust_test");
        assertThat(runtime.exitCode).isNull();
    }

    @Test
    void bootstrapExitsWhenTokenMissing() {
        LicenseGate gate = new LicenseGate(publicKeys, workingDir, MapEnv.empty(), runtime);

        withCapturedStderr(() -> assertThatThrownBy(gate::bootstrap).isInstanceOf(IllegalStateException.class));

        assertThat(runtime.exitCode).isEqualTo(LicenseGate.EXIT_CODE);
    }

    @Test
    void bootstrapExitsWhenSignatureBad() {
        // Sign with a different key than the one loaded into publicKeys.
        KeyPair rogue = Ed25519.generate(new SecureRandom());
        String token = sign(rogue, defaultPayload("test-2026"));
        LicenseGate gate = new LicenseGate(publicKeys, workingDir, MapEnv.env(LicenseGate.ENV_VAR, token), runtime);

        withCapturedStderr(() -> assertThatThrownBy(gate::bootstrap).isInstanceOf(IllegalStateException.class));

        assertThat(runtime.exitCode).isEqualTo(LicenseGate.EXIT_CODE);
    }

    @Test
    void bootstrapExitsWhenExpired() {
        Map<Object, Object> payload = defaultPayload("test-2026");
        payload.put("exp", Instant.now().minusSeconds(60).getEpochSecond());
        String token = sign(keyPair, payload);
        LicenseGate gate = new LicenseGate(publicKeys, workingDir, MapEnv.env(LicenseGate.ENV_VAR, token), runtime);

        withCapturedStderr(() -> assertThatThrownBy(gate::bootstrap).isInstanceOf(IllegalStateException.class));

        assertThat(runtime.exitCode).isEqualTo(LicenseGate.EXIT_CODE);
    }

    private String validToken() {
        return sign(keyPair, defaultPayload("test-2026"));
    }

    private static Map<Object, Object> defaultPayload(String kid) {
        long now = Instant.now().getEpochSecond();
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("v", 1L);
        map.put("iss", "multiforge.example");
        map.put("sub", "cust_test");
        map.put("iat", now - 60);
        map.put("nbf", now - 60);
        map.put("exp", Cbor.NULL);
        map.put("features", List.of("core"));
        map.put("kid", kid);
        return map;
    }

    private static String sign(KeyPair kp, Map<Object, Object> payload) {
        byte[] payloadBytes = Cbor.encode(payload);
        byte[] signature = Ed25519.sign(kp.getPrivate(), payloadBytes);
        return Base64Url.encode(payloadBytes) + "." + Base64Url.encode(signature);
    }

    private static void withCapturedStderr(Runnable action) {
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setErr(original);
        }
    }

    private static final class RecordingExiter implements LicenseGate.Exiter {
        Integer exitCode;

        @Override
        public void exit(int status) {
            exitCode = status;
            throw new IllegalStateException("exit(" + status + ")");
        }
    }

    private static final class MapEnv implements LicenseGate.Environment {
        private final Map<String, String> env;
        private final Map<String, String> sys;

        private MapEnv(Map<String, String> env, Map<String, String> sys) {
            this.env = env;
            this.sys = sys;
        }

        static MapEnv empty() {
            return new MapEnv(new HashMap<>(), new HashMap<>());
        }

        static MapEnv env(String name, String value) {
            Map<String, String> e = new HashMap<>();
            e.put(name, value);
            return new MapEnv(e, new HashMap<>());
        }

        static MapEnv sys(String name, String value) {
            Map<String, String> s = new HashMap<>();
            s.put(name, value);
            return new MapEnv(new HashMap<>(), s);
        }

        @Override
        public String getEnv(String name) {
            return env.get(name);
        }

        @Override
        public String getProperty(String name) {
            return sys.get(name);
        }
    }
}
