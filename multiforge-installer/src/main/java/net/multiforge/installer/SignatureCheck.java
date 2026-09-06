/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PublicKey;
import java.util.Base64;
import net.multiforge.license.internal.Ed25519;

/**
 * Ed25519 signature verification for artifacts the installer writes to
 * disk, checked against an embedded public key so a tampered or
 * mis-published jar is caught before {@code install} lays anything down.
 *
 * <p>The embedded key lives at {@link #PUBLIC_KEY_RESOURCE} — 32 raw
 * Ed25519 public-key bytes, base64-encoded, one key per file, {@code #}
 * comment lines allowed. See that resource file's header for the
 * current key's provenance.
 *
 * <p>Reuses {@link Ed25519} from {@code multiforge-license} (already an
 * {@code implementation} dependency of this module) rather than
 * duplicating the RFC 8032 raw-key encoding logic.
 */
public final class SignatureCheck {

    private static final String PUBLIC_KEY_RESOURCE = "/multiforge-installer-signing.pub";

    private SignatureCheck() {}

    /**
     * Loads and decodes the embedded installer signing public key.
     *
     * @throws IllegalStateException if the resource is missing, empty,
     *         or not a valid base64-encoded 32-byte Ed25519 key.
     */
    public static PublicKey loadEmbeddedPublicKey() {
        try (InputStream in = SignatureCheck.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("embedded signing key resource missing: " + PUBLIC_KEY_RESOURCE);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return decodePublicKey(text);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read embedded signing key resource", e);
        }
    }

    /** Parses a {@code #}-comment-tolerant, one-key-per-file base64 public key document. */
    static PublicKey decodePublicKey(String resourceText) {
        for (String line : resourceText.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            byte[] raw = Base64.getDecoder().decode(trimmed);
            return Ed25519.publicKeyFromRaw(raw);
        }
        throw new IllegalStateException("no key line found in signing key resource");
    }

    /** Decodes a base64-encoded Ed25519 signature (64 raw bytes). */
    static byte[] decodeSignature(String base64Signature) {
        return Base64.getDecoder().decode(base64Signature.strip());
    }

    /**
     * Verifies {@code data} against {@code signature} using {@code key}.
     * Never throws on a bad signature — returns {@code false}, matching
     * {@link Ed25519#verify}. A malformed (non-base64, wrong-length)
     * signature is treated as a verification failure, not an error, so
     * callers can uniformly branch on the boolean.
     */
    public static boolean verify(byte[] data, byte[] signature, PublicKey key) {
        return Ed25519.verify(key, data, signature);
    }

    /**
     * Convenience overload: reads a base64-encoded signature file and
     * verifies {@code artifact}'s bytes against it with {@code key}.
     */
    public static boolean verifyFile(java.nio.file.Path artifact, java.nio.file.Path signatureFile, PublicKey key)
            throws IOException {
        byte[] data = Files.readAllBytes(artifact);
        byte[] signature;
        try {
            signature = decodeSignature(Files.readString(signatureFile, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformedBase64) {
            return false;
        }
        return verify(data, signature, key);
    }
}
