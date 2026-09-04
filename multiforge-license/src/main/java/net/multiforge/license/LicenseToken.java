/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Decoded, signature-verified license payload. See {@code docs/license.md}
 * for the wire format.
 */
public record LicenseToken(
        int schemaVersion,
        String issuer,
        String subject,
        Instant issuedAt,
        Instant notBefore,
        Optional<Instant> expiresAt,
        List<String> features,
        String keyId) {

    public boolean hasFeature(String feature) {
        return features.contains(feature);
    }
}
