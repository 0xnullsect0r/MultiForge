/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license;

public final class LicenseException extends RuntimeException {

    public enum Reason {
        MISSING,
        MALFORMED,
        UNKNOWN_KID,
        BAD_SIGNATURE,
        NOT_YET_VALID,
        EXPIRED,
        MISSING_FEATURE,
        SCHEMA_VERSION,
    }

    private final Reason reason;

    public LicenseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public LicenseException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
