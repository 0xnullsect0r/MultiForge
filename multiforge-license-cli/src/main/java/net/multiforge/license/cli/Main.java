/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.multiforge.license.internal.Base64Url;
import net.multiforge.license.internal.Cbor;
import net.multiforge.license.internal.Ed25519;

/**
 * CLI entry point for MultiForge license operations.
 *
 * <pre>
 *   multiforge-license-cli keygen --out prod-2026
 *   multiforge-license-cli sign   --key prod-2026.priv \
 *                                 --kid prod-2026 \
 *                                 --iss multiforge.example \
 *                                 --sub cust_abcd1234 \
 *                                 [--exp 2027-01-01T00:00:00Z] \
 *                                 [--feature core --feature enterprise]
 * </pre>
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String cmd = args[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        switch (cmd) {
            case "keygen" -> keygen(rest);
            case "sign" -> sign(rest);
            case "help", "-h", "--help" -> usage();
            default -> {
                System.err.println("Unknown subcommand: " + cmd);
                usage();
                System.exit(2);
            }
        }
    }

    private static void keygen(String[] args) throws IOException {
        Map<String, String> opts = parseOptions(args);
        String out = requireOption(opts, "--out", "prefix for output files");

        KeyPair kp = Ed25519.generate(new SecureRandom());
        byte[] rawPublic = Ed25519.rawFromPublicKey(kp.getPublic());
        // Extract the 32-byte seed by round-tripping through the encoded form.
        // JDK's EdDSAPrivateKey#getBytes() returns the raw seed.
        byte[] rawPrivate = extractSeed(kp.getPrivate());

        Path privPath = Path.of(out + ".priv");
        Path pubPath = Path.of(out + ".pub");
        Files.writeString(privPath, Base64Url.encode(rawPrivate) + "\n", StandardCharsets.UTF_8);
        Files.writeString(pubPath, Base64Url.encode(rawPublic) + "\n", StandardCharsets.UTF_8);
        try {
            privPath.toFile().setReadable(false, false);
            privPath.toFile().setReadable(true, true);
            privPath.toFile().setWritable(false, false);
            privPath.toFile().setWritable(true, true);
        } catch (SecurityException ignored) {
            // Best-effort chmod 600.
        }
        System.out.println("Wrote " + privPath + " (private, chmod 600)");
        System.out.println("Wrote " + pubPath + " (public, add to keys.properties as '<kid>="
                + Base64Url.encode(rawPublic) + "')");
    }

    private static void sign(String[] args) throws IOException {
        List<String> features = new ArrayList<>();
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--feature".equals(a)) {
                features.add(requireNext(args, ++i, "--feature"));
            } else if (a.startsWith("--")) {
                opts.put(a, requireNext(args, ++i, a));
            } else {
                throw new IllegalArgumentException("Unexpected argument: " + a);
            }
        }
        String keyPath = requireOption(opts, "--key", "path to private key file (base64url raw seed)");
        String kid = requireOption(opts, "--kid", "public-key id to embed in the token");
        String iss = requireOption(opts, "--iss", "issuer domain");
        String sub = requireOption(opts, "--sub", "customer id");
        String expStr = opts.get("--exp");
        String nbfStr = opts.get("--nbf");
        if (features.isEmpty()) features.add("core");

        byte[] seed = Base64Url.decode(
                Files.readString(Path.of(keyPath), StandardCharsets.UTF_8).trim());
        PrivateKey pk = Ed25519.privateKeyFromSeed(seed);

        long now = Instant.now().getEpochSecond();
        long nbf = nbfStr == null ? now : parseInstant(nbfStr).getEpochSecond();
        Long exp = expStr == null ? null : parseInstant(expStr).getEpochSecond();

        Map<Object, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1L);
        payload.put("iss", iss);
        payload.put("sub", sub);
        payload.put("iat", now);
        payload.put("nbf", nbf);
        payload.put("exp", exp == null ? Cbor.NULL : exp);
        payload.put("features", features);
        payload.put("kid", kid);

        byte[] payloadBytes = Cbor.encode(payload);
        byte[] signature = Ed25519.sign(pk, payloadBytes);
        String token = Base64Url.encode(payloadBytes) + "." + Base64Url.encode(signature);
        System.out.println(token);
    }

    private static Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Expected ISO-8601 UTC instant (e.g. 2027-01-01T00:00:00Z): " + s, e);
        }
    }

    private static byte[] extractSeed(PrivateKey privateKey) {
        if (privateKey instanceof java.security.interfaces.EdECPrivateKey ed) {
            return ed.getBytes()
                    .orElseThrow(() -> new IllegalStateException(
                            "Ed25519 private key does not expose its raw seed (unusual JDK configuration)"));
        }
        throw new IllegalStateException(
                "Expected EdECPrivateKey, got " + privateKey.getClass().getName());
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + a);
            opts.put(a, requireNext(args, ++i, a));
        }
        return opts;
    }

    private static String requireOption(Map<String, String> opts, String name, String description) {
        String v = opts.get(name);
        if (v == null) throw new IllegalArgumentException("Missing required option " + name + " (" + description + ")");
        return v;
    }

    private static String requireNext(String[] args, int idx, String name) {
        if (idx >= args.length) throw new IllegalArgumentException("Option " + name + " requires a value");
        return args[idx];
    }

    private static void usage() {
        System.err.println(
                """
                MultiForge license CLI

                  keygen  --out <prefix>
                            Generate a new Ed25519 keypair.
                            Writes <prefix>.priv (raw seed, base64url, chmod 600)
                            and <prefix>.pub (raw public key, base64url).

                  sign    --key <path-to-.priv>
                          --kid <public-key-id>
                          --iss <issuer-domain>
                          --sub <customer-id>
                          [--nbf <ISO-8601 UTC>]
                          [--exp <ISO-8601 UTC>]
                          [--feature <name> ...]
                            Sign and print a license token.

                Examples:
                  multiforge-license-cli keygen --out prod-2026
                  multiforge-license-cli sign --key prod-2026.priv \\
                      --kid prod-2026 --iss multiforge.example \\
                      --sub cust_abcd1234 --exp 2027-01-01T00:00:00Z
                """
                        .stripIndent());
        System.err.flush();
    }
}
