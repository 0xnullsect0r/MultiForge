/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Sanity check that the production public keys shipped in
 * {@code multiforge/license/keys/keys.properties} parse — a typo in
 * a base64url character would break every license verify at server
 * boot.
 */
class BakedInPublicKeysTest {

    @Test
    void bakedInKeysLoad() {
        PublicKeys keys = PublicKeys.loadBakedIn();
        assertThat(keys.size()).isGreaterThan(0);
    }

    @Test
    void production2026KeyIsPresent() {
        PublicKeys keys = PublicKeys.loadBakedIn();
        assertThat(keys.byKid("multiforge-prod-2026-1")).isNotNull();
    }
}
