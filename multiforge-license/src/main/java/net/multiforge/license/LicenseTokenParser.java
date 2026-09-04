/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.multiforge.license.internal.Base64Url;
import net.multiforge.license.internal.Cbor;
import net.multiforge.license.internal.Ed25519;
import net.multiforge.license.internal.PublicKeys;

public final class LicenseTokenParser {

    public static final String FEATURE_CORE = "core";
    public static final int SCHEMA_VERSION = 1;

    private final PublicKeys publicKeys;

    public LicenseTokenParser(PublicKeys publicKeys) {
        this.publicKeys = publicKeys;
    }

    public LicenseToken parse(String token) {
        if (token == null || token.isBlank()) {
            throw new LicenseException(LicenseException.Reason.MISSING, "License token is missing");
        }
        String trimmed = token.trim();
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot >= trimmed.length() - 1 || trimmed.indexOf('.', dot + 1) >= 0) {
            throw new LicenseException(
                    LicenseException.Reason.MALFORMED, "License token must be <payload>.<signature>");
        }
        byte[] payloadBytes;
        byte[] signatureBytes;
        try {
            payloadBytes = Base64Url.decode(trimmed.substring(0, dot));
            signatureBytes = Base64Url.decode(trimmed.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "License token is not valid base64url", e);
        }

        Object decoded;
        try {
            decoded = Cbor.decode(payloadBytes);
        } catch (RuntimeException e) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "License payload is not valid CBOR", e);
        }
        if (!(decoded instanceof Map<?, ?> raw)) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "License payload is not a CBOR map");
        }

        int version = (int) requireLong(raw, "v");
        if (version != SCHEMA_VERSION) {
            throw new LicenseException(
                    LicenseException.Reason.SCHEMA_VERSION,
                    "Unsupported license schema version " + version + " (expected " + SCHEMA_VERSION + ")");
        }

        String kid = requireText(raw, "kid");
        var publicKey = publicKeys.byKid(kid);
        if (publicKey == null) {
            throw new LicenseException(
                    LicenseException.Reason.UNKNOWN_KID,
                    "License signed by unknown key id '" + kid + "' — is your MultiForge build outdated?");
        }
        if (!Ed25519.verify(publicKey, payloadBytes, signatureBytes)) {
            throw new LicenseException(
                    LicenseException.Reason.BAD_SIGNATURE, "License signature is invalid for key id '" + kid + "'");
        }

        String issuer = requireText(raw, "iss");
        String subject = requireText(raw, "sub");
        Instant issuedAt = Instant.ofEpochSecond(requireLong(raw, "iat"));
        Instant notBefore = Instant.ofEpochSecond(requireLong(raw, "nbf"));
        Optional<Instant> expiresAt = optionalLong(raw, "exp").map(Instant::ofEpochSecond);

        Object featuresObj = raw.get("features");
        if (!(featuresObj instanceof List<?> rawFeatures)) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "License field 'features' must be an array");
        }
        List<String> features = new ArrayList<>(rawFeatures.size());
        for (Object f : rawFeatures) {
            if (!(f instanceof String s)) {
                throw new LicenseException(
                        LicenseException.Reason.MALFORMED, "License 'features' entry is not text: " + f);
            }
            features.add(s);
        }

        return new LicenseToken(version, issuer, subject, issuedAt, notBefore, expiresAt, List.copyOf(features), kid);
    }

    public void checkTime(LicenseToken token, Instant now) {
        if (now.isBefore(token.notBefore())) {
            throw new LicenseException(
                    LicenseException.Reason.NOT_YET_VALID, "License is not valid until " + token.notBefore());
        }
        token.expiresAt().ifPresent(exp -> {
            if (!now.isBefore(exp)) {
                throw new LicenseException(LicenseException.Reason.EXPIRED, "License expired at " + exp);
            }
        });
    }

    public void checkFeature(LicenseToken token, String feature) {
        if (!token.hasFeature(feature)) {
            throw new LicenseException(
                    LicenseException.Reason.MISSING_FEATURE,
                    "License does not include required feature '" + feature + "'");
        }
    }

    private static long requireLong(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v instanceof Long l) return l;
        throw new LicenseException(LicenseException.Reason.MALFORMED, "License field '" + key + "' must be an integer");
    }

    private static Optional<Long> optionalLong(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null || v == Cbor.NULL) return Optional.empty();
        if (v instanceof Long l) return Optional.of(l);
        throw new LicenseException(
                LicenseException.Reason.MALFORMED, "License field '" + key + "' must be integer or null");
    }

    private static String requireText(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v instanceof String s && !s.isEmpty()) return s;
        throw new LicenseException(
                LicenseException.Reason.MALFORMED, "License field '" + key + "' must be non-empty text");
    }
}
