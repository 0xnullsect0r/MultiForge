/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license.internal;

import java.util.Base64;

public final class Base64Url {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Base64Url() {}

    public static String encode(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    public static byte[] decode(String s) {
        return DECODER.decode(s);
    }
}
