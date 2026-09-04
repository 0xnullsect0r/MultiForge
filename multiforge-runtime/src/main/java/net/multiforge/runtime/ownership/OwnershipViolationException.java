/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.ownership;

/**
 * Thrown by {@link OwnershipEnforcer} only when running in {@link
 * OwnershipEnforcer.Mode#STRICT}, i.e. under {@code
 * -Dmultiforge.ownership.mode=strict}. Never thrown in the default {@code
 * REROUTE} mode — see CLAUDE.md rule 5 (auto-reroute + warn is the
 * default; never throw from a mod's code path).
 */
public final class OwnershipViolationException extends RuntimeException {

    private final String site;
    private final Domain actualDomain;

    public OwnershipViolationException(String site, Thread thread, Domain actualDomain) {
        super("Ownership violation at [" + site + "] on thread '" + thread.getName() + "' (domain=" + actualDomain
                + ")");
        this.site = site;
        this.actualDomain = actualDomain;
    }

    public String site() {
        return site;
    }

    public Domain actualDomain() {
        return actualDomain;
    }
}
