/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
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
