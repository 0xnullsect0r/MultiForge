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
package net.multiforge.runtime.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression pin for /67 round-6 fork D HIGH: {@code
 * net.multiforge.neoforge.entity.EntityMigrationBridge#onPositionChanged(Entity)} was fired TWICE
 * per {@code Entity.move()}-driven position update.
 *
 * <p>{@code multiforge-patches/05-entity-migration/net/minecraft/world/entity/Entity.java.patch}
 * originally carried two hunks that both called {@code
 * EntityMigrationBridge.onPositionChanged(this)}:
 *
 * <ul>
 *   <li>post-{@code this.setPos(...)} inside {@code move()} (the outer, redundant call — removed
 *       by this fix);
 *   <li>post-{@code this.levelCallback.onMove()} inside {@code setPosRaw(...)} (the single
 *       chokepoint every Vanilla position-changing call funnels through — kept).
 * </ul>
 *
 * <p>Vanilla's {@code Entity.setPos(double, double, double)} calls {@code setPosRaw(...)}
 * internally (confirmed against the vendored NeoForge source at commit time — see the fix
 * commit), so the {@code move()}-site call was always a duplicate of the {@code setPosRaw}-site
 * call for every {@code move()}-driven update: one extra {@code ConcurrentHashMap} lookup pair
 * per entity, per tick, for no behavioral gain (both call sites route into the bridge's
 * idempotent CAS check).
 *
 * <p>{@code multiforge-runtime} has no Minecraft classpath (by design — see {@code
 * EntityMigrationCoordinator}'s class javadoc), so this test cannot construct a real Vanilla
 * {@code Entity} and spy on the bridge dynamically the way an in-game test would. Instead it pins
 * the regression at its actual source: the checked-in patch content. A future re-introduction of
 * the redundant hunk (accidentally or via a bad rebase) fails this test immediately, without
 * requiring a full NeoForge vendor + compile cycle to notice.
 */
class EntityBridgeDoubleFireTest {

    private static final String PATCH_RELATIVE_PATH =
            "multiforge-patches/05-entity-migration/net/minecraft/world/entity/Entity.java.patch";

    /** Matches an actual invocation line, not a comment mentioning the method name. */
    private static final Pattern ON_POSITION_CHANGED_CALL = Pattern.compile(
            "^\\+\\s*net\\.multiforge\\.neoforge\\.entity\\.EntityMigrationBridge\\.onPositionChanged\\(this\\);\\s*$");

    @Test
    void entityJavaPatchFiresOnPositionChangedExactlyOnce() {
        Path patchFile = findRepoRoot().resolve(PATCH_RELATIVE_PATH);
        assertThat(Files.isRegularFile(patchFile))
                .as("expected patch at %s — update PATCH_RELATIVE_PATH if the patch moved", patchFile)
                .isTrue();

        List<String> lines;
        try {
            lines = Files.readAllLines(patchFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        long callSites = lines.stream()
                .filter(ON_POSITION_CHANGED_CALL.asMatchPredicate())
                .count();

        assertThat(callSites)
                .as("Entity.java.patch must call EntityMigrationBridge.onPositionChanged(this) from "
                        + "exactly one hunk (the setPosRaw/onMove funnel) — a count of 2 means the "
                        + "redundant post-setPos() hunk (move()'s outer call) has been reintroduced, "
                        + "doubling the ConcurrentHashMap lookup on the entity-tick hot path")
                .isEqualTo(1L);
    }

    /**
     * Walks up from the working directory (Gradle runs tests with the module directory as cwd) to
     * find the directory containing {@code settings.gradle.kts} — the repository root under which
     * {@code multiforge-patches/} lives.
     */
    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "could not locate repository root (no settings.gradle.kts) walking up from " + dir);
    }
}
