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
package net.multiforge.neoforge.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.diagnostics.EntityCensus;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.jetbrains.annotations.ApiStatus;

/**
 * The entity census behind {@code /multiforge entities}, and the heal for
 * entities left in limbo.
 *
 * <p>An entity is in limbo when its entity storage and the level disagree on
 * it: it sits in an accessible section but is not visible (not tracked, so no
 * client sees it and no mob cap counts it), or in a ticking section but not
 * in the tick list (it never ticks, so never despawns), or the reverse. A
 * visibility change racing a region worker's entity move left entities like
 * that (fixed by {@code RegionPhase}); they are saved and reload as normal
 * mobs, so worlds that ran the race keep them. The heal re-applies the
 * missing tracking or ticking start (or end) for each mismatch, in Vanilla's
 * order: ticking ends, tracking ends, tracking starts, ticking starts.
 *
 * <p>Server thread only, with no region running: at the barrier (the
 * automatic audit, every {@value #AUTO_INTERVAL_TICKS} ticks) or from a
 * command. The census reads the entity storage under its leaf lock, and the
 * tick list after releasing it (the tick list's monitor is taken before the
 * storage lock elsewhere, never after).
 */
@ApiStatus.Internal
public final class EntityAudit {
    /** Ticks between automatic audits (and at most one gap warning per interval). */
    public static final int AUTO_INTERVAL_TICKS = 1200;

    /** Mismatched entities described per census. */
    static final int SAMPLES = 5;

    /** {@code -Dmultiforge.entities.autoHeal=false} keeps the periodic audit but does not heal. */
    private static final boolean AUTO_HEAL = !"false".equalsIgnoreCase(System.getProperty("multiforge.entities.autoHeal", "true").trim());

    private EntityAudit() {}

    /**
     * One entity manager's contents, read under its lock: every sectioned entity
     * once, in {@code hidden} or {@code accessible} (and {@code ticking} if its
     * section ticks) by the effective status of its section, and the visible
     * lookup.
     */
    public record Raw<T>(int known, List<T> hidden, List<T> accessible, List<T> ticking, List<T> visible) {}

    /**
     * Take {@code level}'s census; with {@code heal}, then re-apply the
     * transitions its mismatches are missing. The census returned is the one
     * from before the heal, with {@link EntityCensus#healed()} set.
     */
    public static EntityCensus take(ServerLevel level, boolean heal) {
        PersistentEntitySectionManager<Entity> manager = level.mfEntityManager();
        Raw<Entity> raw = manager.mfCensus();
        List<Entity> listed = new ArrayList<>();
        level.mfCopyEntityTickList(listed);

        Set<Entity> accessible = identitySet(raw.accessible());
        Set<Entity> visible = identitySet(raw.visible());
        Set<Entity> ticking = identitySet(raw.ticking());
        Set<Entity> listedSet = identitySet(listed);
        List<Entity> accessibleNotVisible = missing(raw.accessible(), visible);
        List<Entity> visibleNotAccessible = missing(raw.visible(), accessible);
        List<Entity> tickingNotListed = missing(raw.ticking(), listedSet);
        List<Entity> listedNotTicking = missing(listed, ticking);

        Map<Long, Map<String, Integer>> byRegion = new HashMap<>();
        count(level, raw.hidden(), byRegion);
        count(level, raw.accessible(), byRegion);

        List<String> samples = new ArrayList<>();
        sample(level, accessibleNotVisible, "accessible-not-visible", samples);
        sample(level, visibleNotAccessible, "visible-not-accessible", samples);
        sample(level, tickingNotListed, "ticking-not-listed", samples);
        sample(level, listedNotTicking, "listed-not-ticking", samples);

        int healed = 0;
        if (heal) {
            for (Entity e : listedNotTicking) manager.mfStopTicking(e);
            for (Entity e : visibleNotAccessible) manager.mfStopTracking(e);
            for (Entity e : accessibleNotVisible) manager.mfStartTracking(e);
            for (Entity e : tickingNotListed) manager.mfStartTicking(e);
            healed = listedNotTicking.size()
                    + visibleNotAccessible.size()
                    + accessibleNotVisible.size()
                    + tickingNotListed.size();
        }
        return new EntityCensus(
                level.dimension().location().toString(),
                raw.known(),
                raw.hidden().size(),
                raw.accessible().size(),
                raw.visible().size(),
                raw.ticking().size(),
                listed.size(),
                accessibleNotVisible.size(),
                visibleNotAccessible.size(),
                tickingNotListed.size(),
                listedNotTicking.size(),
                byRegion,
                samples,
                healed);
    }

    /**
     * The periodic audit, at the barrier of every {@value #AUTO_INTERVAL_TICKS}th
     * server tick (regionized mode only): heal any limbo entities, count them in
     * {@code entity.limbo.healed}, and log a rate-limited warning while any gap
     * is open.
     */
    public static void autoAudit(ServerLevel level) {
        if (level.getServer().getTickCount() % AUTO_INTERVAL_TICKS != 0) return;
        EntityCensus census = take(level, AUTO_HEAL);
        if (census.healthy()) return;
        ProbeRegistry.add("entity.limbo.healed", census.healed());
        ViolationLogger.warn(
                "entity.limbo",
                census.summaryLine()
                        + (AUTO_HEAL ? "" : " (auto-heal off)")
                        + (census.samples().isEmpty() ? "" : "; e.g. " + census.samples().get(0)));
    }

    private static Set<Entity> identitySet(List<Entity> entities) {
        Set<Entity> set = Collections.newSetFromMap(new IdentityHashMap<>(entities.size() * 2));
        set.addAll(entities);
        return set;
    }

    private static List<Entity> missing(List<Entity> from, Set<Entity> in) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : from) {
            if (!in.contains(e)) out.add(e);
        }
        return out;
    }

    private static void count(ServerLevel level, List<Entity> entities, Map<Long, Map<String, Integer>> byRegion) {
        for (Entity e : entities) {
            BlockPos pos = e.blockPosition();
            long region = RegionizedTickCoordinator.regionIdAt(level, pos.getX() >> 4, pos.getZ() >> 4);
            byRegion.computeIfAbsent(region, r -> new HashMap<>())
                    .merge(EntityType.getKey(e.getType()).toString(), 1, Integer::sum);
        }
    }

    private static void sample(ServerLevel level, List<Entity> entities, String kind, List<String> into) {
        for (Entity e : entities) {
            if (into.size() >= SAMPLES) return;
            BlockPos pos = e.blockPosition();
            into.add(EntityType.getKey(e.getType()) + " '" + e.getName().getString() + "' at (" + pos.getX() + ", "
                    + pos.getY() + ", " + pos.getZ() + ") "
                    + EntityCensus.regionName(RegionizedTickCoordinator.regionIdAt(level, pos.getX() >> 4, pos.getZ() >> 4))
                    + " [" + kind + "]");
        }
    }
}
