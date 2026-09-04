/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license.internal;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Thin wrapper over the JDK 15+ built-in Ed25519 provider. All keys are
 * exchanged in their 32-byte raw form (per RFC 8032).
 */
public final class Ed25519 {

    private Ed25519() {}

    /** 32-byte raw Ed25519 public key. */
    public static PublicKey publicKeyFromRaw(byte[] raw32) {
        if (raw32.length != 32) throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        // RFC 8032 §5.1.2: the encoded public key is the 32-byte little-endian
        // y-coordinate with the top bit of the last byte set to the sign of x.
        byte[] y = raw32.clone();
        boolean xOdd = (y[31] & 0x80) != 0;
        y[31] &= 0x7F;
        // BigInteger wants big-endian; reverse.
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) be[i] = y[31 - i];
        BigInteger yInt = new BigInteger(1, be);
        try {
            EdECPoint point = new EdECPoint(xOdd, yInt);
            EdECPublicKeySpec spec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, point);
            return KeyFactory.getInstance("Ed25519").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 not available in this JDK", e);
        }
    }

    /** 32-byte raw Ed25519 private key seed. */
    public static PrivateKey privateKeyFromSeed(byte[] seed32) {
        if (seed32.length != 32) throw new IllegalArgumentException("Ed25519 seed must be 32 bytes");
        try {
            EdECPrivateKeySpec spec = new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed32);
            return KeyFactory.getInstance("Ed25519").generatePrivate(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 not available in this JDK", e);
        }
    }

    /** Serialize an Ed25519 public key back to its 32-byte raw form. */
    public static byte[] rawFromPublicKey(PublicKey pk) {
        if (!(pk instanceof EdECPublicKey edPk)) {
            throw new IllegalArgumentException("Not an Ed25519 public key: " + pk.getClass());
        }
        EdECPoint point = edPk.getPoint();
        byte[] be = point.getY().toByteArray();
        // Left-pad or trim to exactly 32 bytes, big-endian.
        byte[] be32 = new byte[32];
        if (be.length <= 32) {
            System.arraycopy(be, 0, be32, 32 - be.length, be.length);
        } else {
            // Leading sign byte from BigInteger.
            System.arraycopy(be, be.length - 32, be32, 0, 32);
        }
        // Reverse to little-endian.
        byte[] le = new byte[32];
        for (int i = 0; i < 32; i++) le[i] = be32[31 - i];
        if (point.isXOdd()) le[31] |= (byte) 0x80;
        return le;
    }

    public static KeyPair generate(SecureRandom random) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            kpg.initialize(NamedParameterSpec.ED25519, random);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 not available in this JDK", e);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] message) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(message);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 sign failed", e);
        }
    }

    public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
