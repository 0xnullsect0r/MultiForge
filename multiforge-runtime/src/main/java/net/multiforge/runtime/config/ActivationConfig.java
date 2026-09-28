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
package net.multiforge.runtime.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.ApiStatus;

/**
 * The {@code [entities]} activation-range and push-cap knobs of {@code
 * multiforge-server.toml}.
 *
 * <p><b>Activation range.</b> A mob further than its category's range from
 * every player (horizontally; within 256 blocks vertically) is <em>inactive</em>:
 * it runs a full tick only one tick in {@link #wakeInterval()} (staggered by
 * entity id), and on the other ticks only ages ({@code tickCount}, {@code
 * noActionTime}, a baby's growth), so despawn timing and growing up are kept.
 * Everything that is not a {@code Mob} (items, projectiles, minecarts, Create
 * contraptions) and a list of busy or special mobs always tick; see {@code
 * docs/compatibility.md}. A region whose last tick took over 40 ms doubles the
 * wake interval of its inactive mobs, and again over 80 ms, up to {@value
 * #MAX_SHED_INTERVAL} ticks. A category whose range is {@code 0} is never throttled.
 *
 * <p><b>Push cap.</b> A living entity pushes at most {@link
 * #maxEntityCollisions()} of the entities overlapping it per tick ({@code 0} =
 * no cap). Cramming damage still counts every overlapping entity, exactly as
 * Vanilla does.
 *
 * <p>Every key can be overridden with {@code -Dmultiforge.entities.<key>=...}
 * ({@link #effective()}); the exempt list takes a comma-separated value. Mode
 * {@code off} runs Vanilla and ignores all of it.
 *
 * @param activation whether distant mobs are throttled at all
 * @param monsterRange range for monsters (blocks)
 * @param animalRange range for animals and other creatures
 * @param villagerRange range for villagers and wandering traders
 * @param flyingRange range for flying monsters (ghasts, phantoms)
 * @param raiderRange range for raiders (pillagers, vindicators, witches, ...)
 * @param waterRange range for water mobs (squid, fish, dolphins, axolotls)
 * @param ambientRange range for ambient mobs (bats)
 * @param wakeInterval an inactive mob runs a full tick once per this many ticks
 * @param maxEntityCollisions entities a living entity pushes per tick; 0 = no cap
 * @param exempt entity type ids ({@code modid:name}) or tags ({@code #modid:tag})
 *     that always tick, in addition to the {@code #multiforge:activation_exempt} tag
 */
@ApiStatus.Internal
public record ActivationConfig(
        boolean activation,
        int monsterRange,
        int animalRange,
        int villagerRange,
        int flyingRange,
        int raiderRange,
        int waterRange,
        int ambientRange,
        int wakeInterval,
        int maxEntityCollisions,
        List<String> exempt) {

    /** The longest wake interval load shedding stretches to, in ticks. */
    public static final int MAX_SHED_INTERVAL = 80;
    /** A region tick longer than this (and its doublings) stretches inactive mobs' wake interval. */
    public static final long SHED_THRESHOLD_NANOS = 40_000_000L;

    public static final ActivationConfig DEFAULTS =
            new ActivationConfig(true, 32, 32, 32, 32, 48, 16, 16, 20, 8, List.of());

    /** Every key's name under {@code [entities]} and its {@code -Dmultiforge.entities.} override. */
    public static final List<String> KEYS = List.of(
            "activation",
            "monsterRange",
            "animalRange",
            "villagerRange",
            "flyingRange",
            "raiderRange",
            "waterRange",
            "ambientRange",
            "wakeInterval",
            "maxEntityCollisions",
            "activationExempt");

    public ActivationConfig {
        monsterRange = Math.max(0, monsterRange);
        animalRange = Math.max(0, animalRange);
        villagerRange = Math.max(0, villagerRange);
        flyingRange = Math.max(0, flyingRange);
        raiderRange = Math.max(0, raiderRange);
        waterRange = Math.max(0, waterRange);
        ambientRange = Math.max(0, ambientRange);
        wakeInterval = Math.max(1, wakeInterval);
        maxEntityCollisions = Math.max(0, maxEntityCollisions);
        exempt = exempt == null ? List.of() : List.copyOf(exempt);
    }

    /** The largest range of any category (a cheap "near any player at all" bound). */
    public int maxRange() {
        return Math.max(
                Math.max(Math.max(monsterRange, animalRange), Math.max(villagerRange, flyingRange)),
                Math.max(raiderRange, Math.max(waterRange, ambientRange)));
    }

    /** This config with every {@code -Dmultiforge.entities.<key>} override applied. */
    public ActivationConfig effective() {
        return withOverrides(key -> System.getProperty("multiforge.entities." + key));
    }

    /** This config with the given per-key overrides applied (null = keep); unparsable values are ignored. */
    public ActivationConfig withOverrides(UnaryOperator<String> lookup) {
        return new ActivationConfig(
                bool(lookup.apply("activation"), activation),
                integer(lookup.apply("monsterRange"), monsterRange),
                integer(lookup.apply("animalRange"), animalRange),
                integer(lookup.apply("villagerRange"), villagerRange),
                integer(lookup.apply("flyingRange"), flyingRange),
                integer(lookup.apply("raiderRange"), raiderRange),
                integer(lookup.apply("waterRange"), waterRange),
                integer(lookup.apply("ambientRange"), ambientRange),
                integer(lookup.apply("wakeInterval"), wakeInterval),
                integer(lookup.apply("maxEntityCollisions"), maxEntityCollisions),
                list(lookup.apply("activationExempt"), exempt));
    }

    public ActivationConfig withActivation(boolean v) {
        return new ActivationConfig(
                v,
                monsterRange,
                animalRange,
                villagerRange,
                flyingRange,
                raiderRange,
                waterRange,
                ambientRange,
                wakeInterval,
                maxEntityCollisions,
                exempt);
    }

    public ActivationConfig withMaxEntityCollisions(int v) {
        return new ActivationConfig(
                activation,
                monsterRange,
                animalRange,
                villagerRange,
                flyingRange,
                raiderRange,
                waterRange,
                ambientRange,
                wakeInterval,
                v,
                exempt);
    }

    /**
     * The wake interval for a region whose last tick took {@code lastTickNanos}:
     * {@code base}, doubled once for a tick over 40 ms and again for each further
     * doubling of that time, never above {@value #MAX_SHED_INTERVAL} (and never
     * below {@code base}).
     */
    public static int shedInterval(int base, long lastTickNanos) {
        int interval = Math.max(1, base);
        long threshold = SHED_THRESHOLD_NANOS;
        while (lastTickNanos > threshold && interval * 2 <= MAX_SHED_INTERVAL) {
            interval *= 2;
            threshold *= 2;
        }
        return interval;
    }

    /**
     * Whether an inactive entity with id {@code entityId} runs its full tick at
     * {@code gameTime}: once every {@code interval} ticks, staggered by id so the
     * wake-ups of a crowd spread over the interval.
     */
    public static boolean isWakeTick(long gameTime, int entityId, int interval) {
        if (interval <= 1) return true;
        return Math.floorMod(gameTime + entityId, (long) interval) == 0L;
    }

    private static boolean bool(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        return fallback;
    }

    private static int integer(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> list(String raw, List<String> fallback) {
        if (raw == null) return fallback;
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
