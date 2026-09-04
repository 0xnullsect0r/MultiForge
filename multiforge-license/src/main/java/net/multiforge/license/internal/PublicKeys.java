/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads baked-in production Ed25519 public keys from
 * {@code /multiforge/license/keys/keys.properties} on the classpath.
 *
 * <p>File format is one {@code kid=<base64url-32-byte-key>} per line;
 * blank lines and lines starting with {@code #} are ignored.
 */
public final class PublicKeys {

    private static final String KEYS_RESOURCE = "/multiforge/license/keys/keys.properties";
    private static final Pattern LINE = Pattern.compile("^([A-Za-z0-9_.-]+)\\s*=\\s*([A-Za-z0-9_-]+=*)\\s*$");

    private final Map<String, PublicKey> byKid;

    private PublicKeys(Map<String, PublicKey> byKid) {
        this.byKid = Collections.unmodifiableMap(byKid);
    }

    public static PublicKeys loadBakedIn() {
        try (InputStream in = PublicKeys.class.getResourceAsStream(KEYS_RESOURCE)) {
            if (in == null) return new PublicKeys(Map.of());
            return loadFrom(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load baked-in license public keys", e);
        }
    }

    public static PublicKeys loadFrom(String contents) {
        Map<String, PublicKey> map = new LinkedHashMap<>();
        int lineNum = 0;
        for (String rawLine : contents.split("\\r?\\n")) {
            lineNum++;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) throw new IllegalStateException("Malformed keys line " + lineNum + ": " + rawLine);
            String kid = m.group(1);
            byte[] raw = Base64Url.decode(m.group(2));
            map.put(kid, Ed25519.publicKeyFromRaw(raw));
        }
        return new PublicKeys(map);
    }

    public PublicKey byKid(String kid) {
        return byKid.get(kid);
    }

    public int size() {
        return byKid.size();
    }
}
