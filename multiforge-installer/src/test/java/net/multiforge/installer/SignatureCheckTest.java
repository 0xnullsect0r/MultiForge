/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.installer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;
import net.multiforge.license.internal.Ed25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignatureCheckTest {

    private static final byte[] ARTIFACT = "not a real jar, just some bytes to sign".getBytes(StandardCharsets.UTF_8);

    @Test
    void verifyPassesForAGenuineSignature() {
        KeyPair kp = Ed25519.generate(new SecureRandom());
        byte[] signature = Ed25519.sign(kp.getPrivate(), ARTIFACT);

        assertThat(SignatureCheck.verify(ARTIFACT, signature, kp.getPublic())).isTrue();
    }

    @Test
    void verifyFailsWhenArtifactBytesAreCorrupted() {
        KeyPair kp = Ed25519.generate(new SecureRandom());
        byte[] signature = Ed25519.sign(kp.getPrivate(), ARTIFACT);

        byte[] corrupted = ARTIFACT.clone();
        corrupted[0] ^= 0x01;

        assertThat(SignatureCheck.verify(corrupted, signature, kp.getPublic())).isFalse();
    }

    @Test
    void verifyFailsForASignatureFromADifferentKey() {
        KeyPair signer = Ed25519.generate(new SecureRandom());
        KeyPair impostor = Ed25519.generate(new SecureRandom());
        byte[] signature = Ed25519.sign(signer.getPrivate(), ARTIFACT);

        assertThat(SignatureCheck.verify(ARTIFACT, signature, impostor.getPublic()))
                .isFalse();
    }

    @Test
    void verifyFileRoundTripsThroughDiskWithABase64SignatureFile(@TempDir Path dir) throws Exception {
        KeyPair kp = Ed25519.generate(new SecureRandom());
        byte[] signature = Ed25519.sign(kp.getPrivate(), ARTIFACT);

        Path artifactFile = dir.resolve("artifact.jar");
        Path sigFile = dir.resolve("artifact.jar.sig");
        Files.write(artifactFile, ARTIFACT);
        Files.writeString(sigFile, Base64.getEncoder().encodeToString(signature));

        assertThat(SignatureCheck.verifyFile(artifactFile, sigFile, kp.getPublic()))
                .isTrue();

        Files.write(artifactFile, "tampered".getBytes(StandardCharsets.UTF_8));
        assertThat(SignatureCheck.verifyFile(artifactFile, sigFile, kp.getPublic()))
                .isFalse();
    }

    @Test
    void verifyFileTreatsMalformedBase64SignatureAsFailureNotException(@TempDir Path dir) throws Exception {
        KeyPair kp = Ed25519.generate(new SecureRandom());
        Path artifactFile = dir.resolve("artifact.jar");
        Path sigFile = dir.resolve("artifact.jar.sig");
        Files.write(artifactFile, ARTIFACT);
        Files.writeString(sigFile, "not-valid-base64!!!");

        assertThat(SignatureCheck.verifyFile(artifactFile, sigFile, kp.getPublic()))
                .isFalse();
    }

    @Test
    void decodePublicKeyIsCommentAndBlankLineTolerant() {
        KeyPair kp = Ed25519.generate(new SecureRandom());
        String raw = Base64.getEncoder().encodeToString(Ed25519.rawFromPublicKey(kp.getPublic()));
        String document = "# a comment\n\n" + raw + "\n# trailing comment\n";

        PublicKey decoded = SignatureCheck.decodePublicKey(document);

        assertThat(Ed25519.rawFromPublicKey(decoded)).isEqualTo(Ed25519.rawFromPublicKey(kp.getPublic()));
    }

    @Test
    void loadEmbeddedPublicKeyReadsTheBundledResource() {
        // Sanity check on the real embedded resource: it must parse to a
        // usable Ed25519 key without throwing. It's a throwaway key (see
        // the resource file's header) so there's nothing to sign with it
        // here — that's covered by the generated-keypair tests above.
        PublicKey key = SignatureCheck.loadEmbeddedPublicKey();
        assertThat(Ed25519.rawFromPublicKey(key)).hasSize(32);
    }
}
