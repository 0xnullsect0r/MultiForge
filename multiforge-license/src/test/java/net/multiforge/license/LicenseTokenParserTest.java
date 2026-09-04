/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.multiforge.license.internal.Base64Url;
import net.multiforge.license.internal.Cbor;
import net.multiforge.license.internal.Ed25519;
import net.multiforge.license.internal.PublicKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LicenseTokenParserTest {

    private KeyPair keyPair;
    private String kid;
    private LicenseTokenParser parser;

    @BeforeEach
    void setUp() {
        keyPair = Ed25519.generate(new SecureRandom());
        kid = "test-2026";
        String rawB64 = Base64Url.encode(Ed25519.rawFromPublicKey(keyPair.getPublic()));
        PublicKeys keys = PublicKeys.loadFrom("# throwaway\n" + kid + "=" + rawB64 + "\n");
        parser = new LicenseTokenParser(keys);
    }

    @Test
    void parsesAndVerifiesValidToken() {
        String token = signToken(defaultPayload());

        LicenseToken parsed = parser.parse(token);
        assertThat(parsed.schemaVersion()).isEqualTo(1);
        assertThat(parsed.issuer()).isEqualTo("multiforge.example");
        assertThat(parsed.subject()).isEqualTo("cust_test");
        assertThat(parsed.keyId()).isEqualTo(kid);
        assertThat(parsed.features()).containsExactly("core");
        assertThat(parsed.expiresAt()).isEmpty();

        parser.checkTime(parsed, Instant.now());
        parser.checkFeature(parsed, LicenseTokenParser.FEATURE_CORE);
    }

    @Test
    void tamperedSignatureFails() {
        String token = signToken(defaultPayload());
        int dot = token.indexOf('.');
        // Flip a char in the signature segment.
        char[] chars = token.toCharArray();
        int i = dot + 1;
        chars[i] = chars[i] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> parser.parse(tampered))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.BAD_SIGNATURE));
    }

    @Test
    void tamperedPayloadFails() {
        // Reissue with our test key, but mutate the payload after signing.
        Map<Object, Object> payload = defaultPayload();
        byte[] payloadBytes = Cbor.encode(payload);
        byte[] signature = Ed25519.sign(keyPair.getPrivate(), payloadBytes);
        // Flip a byte in the middle of the payload so kid decoding still works.
        byte[] tamperedPayload = payloadBytes.clone();
        int mid = tamperedPayload.length / 2;
        tamperedPayload[mid] ^= 0x01;
        String tampered = Base64Url.encode(tamperedPayload) + "." + Base64Url.encode(signature);

        assertThatThrownBy(() -> parser.parse(tampered))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isIn(LicenseException.Reason.BAD_SIGNATURE, LicenseException.Reason.MALFORMED));
    }

    @Test
    void unknownKidFails() {
        Map<Object, Object> payload = defaultPayload();
        payload.put("kid", "ghost-2099");
        String token = signToken(payload);

        assertThatThrownBy(() -> parser.parse(token))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.UNKNOWN_KID));
    }

    @Test
    void notYetValidFails() {
        Instant future = Instant.now().plusSeconds(3600);
        Map<Object, Object> payload = defaultPayload();
        payload.put("nbf", future.getEpochSecond());
        payload.put("iat", future.getEpochSecond());
        String token = signToken(payload);

        LicenseToken parsed = parser.parse(token);
        assertThatThrownBy(() -> parser.checkTime(parsed, Instant.now()))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.NOT_YET_VALID));
    }

    @Test
    void expiredFails() {
        Instant past = Instant.now().minusSeconds(3600);
        Map<Object, Object> payload = defaultPayload();
        payload.put("exp", past.getEpochSecond());
        String token = signToken(payload);

        LicenseToken parsed = parser.parse(token);
        assertThatThrownBy(() -> parser.checkTime(parsed, Instant.now()))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.EXPIRED));
    }

    @Test
    void missingCoreFeatureFails() {
        Map<Object, Object> payload = defaultPayload();
        payload.put("features", List.of("someOtherFeature"));
        String token = signToken(payload);

        LicenseToken parsed = parser.parse(token);
        assertThatThrownBy(() -> parser.checkFeature(parsed, LicenseTokenParser.FEATURE_CORE))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.MISSING_FEATURE));
    }

    @Test
    void schemaVersionMismatchFails() {
        Map<Object, Object> payload = defaultPayload();
        payload.put("v", 2L);
        String token = signToken(payload);

        assertThatThrownBy(() -> parser.parse(token))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.SCHEMA_VERSION));
    }

    @Test
    void missingTokenFails() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.MISSING));
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.MISSING));
    }

    @Test
    void malformedTokenFails() {
        assertThatThrownBy(() -> parser.parse("no-dot-here"))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.MALFORMED));
        assertThatThrownBy(() -> parser.parse(".only-signature"))
                .isInstanceOfSatisfying(LicenseException.class, ex -> assertThat(ex.reason())
                        .isEqualTo(LicenseException.Reason.MALFORMED));
    }

    private Map<Object, Object> defaultPayload() {
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

    private String signToken(Map<Object, Object> payload) {
        byte[] payloadBytes = Cbor.encode(payload);
        byte[] signature = Ed25519.sign(keyPair.getPrivate(), payloadBytes);
        return Base64Url.encode(payloadBytes) + "." + Base64Url.encode(signature);
    }
}
