/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class Ed25519Test {

    @Test
    void generatedKeypairSignsAndVerifies() {
        KeyPair kp = Ed25519.generate(new SecureRandom());
        byte[] msg = "hello".getBytes();
        byte[] sig = Ed25519.sign(kp.getPrivate(), msg);
        assertThat(Ed25519.verify(kp.getPublic(), msg, sig)).isTrue();
    }

    @Test
    void tamperedMessageFailsVerification() {
        KeyPair kp = Ed25519.generate(new SecureRandom());
        byte[] msg = "hello".getBytes();
        byte[] sig = Ed25519.sign(kp.getPrivate(), msg);
        assertThat(Ed25519.verify(kp.getPublic(), "world".getBytes(), sig)).isFalse();
    }

    @Test
    void wrongKeyFailsVerification() {
        KeyPair a = Ed25519.generate(new SecureRandom());
        KeyPair b = Ed25519.generate(new SecureRandom());
        byte[] msg = "hello".getBytes();
        byte[] sig = Ed25519.sign(a.getPrivate(), msg);
        assertThat(Ed25519.verify(b.getPublic(), msg, sig)).isFalse();
    }

    @Test
    void rawPublicKeyRoundtrips() {
        KeyPair kp = Ed25519.generate(new SecureRandom());
        byte[] raw = Ed25519.rawFromPublicKey(kp.getPublic());
        assertThat(raw).hasSize(32);
        var reconstructed = Ed25519.publicKeyFromRaw(raw);
        byte[] again = Ed25519.rawFromPublicKey(reconstructed);
        assertThat(again).isEqualTo(raw);
    }
}
