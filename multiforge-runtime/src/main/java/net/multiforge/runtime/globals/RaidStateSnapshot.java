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
package net.multiforge.runtime.globals;

/**
 * Immutable snapshot of one Vanilla {@code Raid}'s state, reported back
 * by a {@link RaidsSystem.RaidsTarget#tickRaidsBody()} call after each
 * real {@code Raids.mfTickBody()} invocation (docs/design/global-region.md
 * §5.3's usage sketch; the actual wave-timer state machine stays in
 * Vanilla's own {@code Raid.java}, byte-identical to upstream — CLAUDE.md
 * rule 3 — this record exists purely so {@link RaidsSystem}, the M6
 * debug HUD, and this module's MC-free test suite have something to
 * observe phase transitions against without either module depending on
 * a Minecraft type).
 *
 * <p>{@code raidId} matches Vanilla's own {@code Raid.getId()}, scoped
 * per-world by {@link RaidsSystem}'s internal keying (two different
 * worlds may reuse the same raw id — Vanilla's {@code nextAvailableID}
 * counter is per-{@code Raids} instance, i.e. per {@code ServerLevel}).
 */
public record RaidStateSnapshot(int raidId, RaidPhase phase, int groupsSpawned, int raidOmenLevel, boolean active) {

    /** Coarse phase enum — a pure-Java mirror of the observable points in Vanilla's {@code Raid} lifecycle. */
    public enum RaidPhase {
        PRE_RAID,
        IN_PROGRESS,
        VICTORY,
        LOSS,
        STOPPED
    }
}
