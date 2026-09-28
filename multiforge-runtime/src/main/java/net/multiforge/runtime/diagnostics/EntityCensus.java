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
package net.multiforge.runtime.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.ApiStatus;

/**
 * One level's entity census: what its entity storage knows, against what is
 * visible and what ticks. Taken on the server thread after the barrier by the
 * fork ({@code net.multiforge.neoforge.world.EntityAudit}); rendered here for
 * {@code /multiforge entities}.
 *
 * <p>In a healthy level every entity in an accessible section is visible
 * (tracked), every visible entity is in an accessible section, and the entity
 * tick list holds exactly the entities in ticking sections. The three gaps
 * measure the departures:
 *
 * <ul>
 * <li>{@link #gapKnownVisible()}: known entities neither visible nor in a
 *     hidden section waiting to unload ({@code known - visible - hidden});</li>
 * <li>{@link #gapAccessibleVisible()}: entities in an accessible section but
 *     not visible, plus visible entities in no accessible section;</li>
 * <li>{@link #gapTicking()}: entities in a ticking section missing from the
 *     tick list, plus tick-list entries in no ticking section. Vanilla maps
 *     block-ticking chunks to "tracked", so a stale stop-ticking shows here and
 *     not in the other two.</li>
 * </ul>
 *
 * @param level              dimension id
 * @param known              UUIDs the entity manager knows
 * @param hidden             entities in sections that are not accessible (their chunk is unloading)
 * @param accessible         entities in accessible sections
 * @param visible            entities in the visible (tracked) lookup
 * @param ticking            entities in ticking sections
 * @param tickListed         entries in the level's entity tick list
 * @param accessibleNotVisible entities in an accessible section that are not visible
 * @param visibleNotAccessible visible entities in no accessible section
 * @param tickingNotListed   entities in a ticking section missing from the tick list
 * @param listedNotTicking   tick-list entries in no ticking section
 * @param byRegion           region id ({@code -1} = no region) → entity type → count, over every sectioned entity
 * @param samples            a few mismatched entities, described
 * @param healed             transitions re-applied by a heal that ran after this census, or 0
 */
@ApiStatus.Internal
public record EntityCensus(
        String level,
        int known,
        int hidden,
        int accessible,
        int visible,
        int ticking,
        int tickListed,
        int accessibleNotVisible,
        int visibleNotAccessible,
        int tickingNotListed,
        int listedNotTicking,
        Map<Long, Map<String, Integer>> byRegion,
        List<String> samples,
        int healed) {

    public EntityCensus {
        byRegion = copy(byRegion);
        samples = List.copyOf(samples);
    }

    private static Map<Long, Map<String, Integer>> copy(Map<Long, Map<String, Integer>> in) {
        Map<Long, Map<String, Integer>> out = new TreeMap<>();
        in.forEach((k, v) -> out.put(k, Map.copyOf(v)));
        return java.util.Collections.unmodifiableMap(out);
    }

    /** {@code known - visible - hidden}: known entities neither visible nor waiting to unload. */
    public int gapKnownVisible() {
        return known - visible - hidden;
    }

    public int gapAccessibleVisible() {
        return accessibleNotVisible + visibleNotAccessible;
    }

    public int gapTicking() {
        return tickingNotListed + listedNotTicking;
    }

    public boolean healthy() {
        return gapKnownVisible() == 0 && gapAccessibleVisible() == 0 && gapTicking() == 0;
    }

    /** Transitions a heal would re-apply. */
    public int mismatches() {
        return gapAccessibleVisible() + gapTicking();
    }

    /** Entity type → count over the whole level. */
    public Map<String, Integer> byType() {
        Map<String, Integer> out = new TreeMap<>();
        for (Map<String, Integer> types : byRegion.values()) types.forEach((t, n) -> out.merge(t, n, Integer::sum));
        return out;
    }

    /** Region id → entity count. */
    public Map<Long, Integer> perRegion() {
        Map<Long, Integer> out = new TreeMap<>();
        byRegion.forEach((r, types) ->
                out.put(r, types.values().stream().mapToInt(Integer::intValue).sum()));
        return out;
    }

    // ---------------------------------------------------------------- rendering

    /** {@code "overworld: known=… visible=… …, gaps …"}. */
    public String summaryLine() {
        return String.format(
                Locale.ROOT,
                "%s: known=%d visible=%d accessible=%d hidden=%d ticking-sections=%d tick-list=%d"
                        + " | gaps known-visible=%d accessible-visible=%d ticking-ticklist=%d%s",
                level,
                known,
                visible,
                accessible,
                hidden,
                ticking,
                tickListed,
                gapKnownVisible(),
                gapAccessibleVisible(),
                gapTicking(),
                healed > 0 ? " | healed " + healed : "");
    }

    /** The summary, then the {@code n} most numerous types, then entities per region. */
    public List<String> render(int topN) {
        List<String> out = new ArrayList<>();
        out.add(summaryLine());
        out.add("  top types: " + top(byType(), topN));
        Map<Long, Integer> regions = perRegion();
        List<Map.Entry<Long, Integer>> sorted = new ArrayList<>(regions.entrySet());
        sorted.sort(Map.Entry.<Long, Integer>comparingByValue().reversed());
        StringBuilder sb = new StringBuilder("  per region:");
        int limit = Math.max(topN, 1);
        for (int i = 0; i < sorted.size(); i++) {
            if (i == limit) {
                sb.append(" …(").append(sorted.size() - limit).append(" more)");
                break;
            }
            sb.append(' ')
                    .append(regionName(sorted.get(i).getKey()))
                    .append('=')
                    .append(sorted.get(i).getValue());
        }
        if (sorted.isEmpty()) sb.append(" (none)");
        out.add(sb.toString());
        return out;
    }

    /** One region's entities by type, or a note that the level has none there. */
    public List<String> renderRegion(long regionId, int topN) {
        Map<String, Integer> types = byRegion.get(regionId);
        if (types == null) return List.of(level + ": no entities in " + regionName(regionId));
        int total = types.values().stream().mapToInt(Integer::intValue).sum();
        return List.of(level + " " + regionName(regionId) + ": " + total + " entities", "  types: " + top(types, topN));
    }

    /** The summary, each gap's parts, and the samples; the heal's result when one ran. */
    public List<String> renderAudit() {
        List<String> out = new ArrayList<>();
        out.add(summaryLine());
        out.add(String.format(
                Locale.ROOT,
                "  accessible-not-visible=%d visible-not-accessible=%d ticking-not-listed=%d listed-not-ticking=%d",
                accessibleNotVisible,
                visibleNotAccessible,
                tickingNotListed,
                listedNotTicking));
        for (String s : samples) out.add("  limbo: " + s);
        if (healthy()) out.add("  no limbo entities");
        return out;
    }

    public static String regionName(long id) {
        return id < 0 ? "no-region" : "region#" + id;
    }

    /** {@code "a 12, b 7, …"}: the {@code n} largest counts, ties by name. */
    static String top(Map<String, Integer> counts, int n) {
        if (counts.isEmpty()) return "(none)";
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, sorted.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sorted.get(i).getKey()).append(' ').append(sorted.get(i).getValue());
        }
        if (sorted.size() > n) sb.append(", …(").append(sorted.size() - n).append(" more)");
        return sb.toString();
    }
}
