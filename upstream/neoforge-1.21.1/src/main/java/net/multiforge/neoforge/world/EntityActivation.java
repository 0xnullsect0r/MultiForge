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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.Vec3;
import net.multiforge.runtime.chunk.InstanceRegistry;
import net.multiforge.runtime.config.ActivationConfig;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Entity activation range and the entity push cap ({@code [entities]} in
 * {@code multiforge-server.toml}, {@link ActivationConfig}).
 *
 * <p><b>Activation.</b> {@code ServerLevel.tickNonPassenger} asks {@link
 * #skipTick} first. A {@code Mob} further than its category's range from every
 * player of its level, and not immune (see {@link #isImmune}), runs its full
 * tick only on its wake tick, one tick in {@code wakeInterval}, staggered by
 * entity id; on the other ticks it only ages ({@link #inactiveTick}). Its
 * {@code checkDespawn} runs before {@code tickNonPassenger}, every tick, so an
 * inactive crowd still despawns. Only the region entity pass is throttled:
 * {@link #beginRegion}/{@link #endRegion} bracket it on the ticking thread, and
 * everything else (mode {@code off}, entities the server thread ticks after the
 * regions, a mod calling {@code tickNonPassenger}) ticks as in Vanilla. The
 * player positions are snapshotted per level on the server thread before the
 * regions run ({@link #snapshotPlayers}), so region workers never read the
 * level's player list.
 *
 * <p><b>Load shedding.</b> A region whose last tick took over 40 ms doubles
 * its inactive mobs' wake interval, and again for each doubling of that time,
 * up to 80 ticks ({@link ActivationConfig#shedInterval}). Active mobs are not
 * affected.
 *
 * <p><b>Push cap.</b> {@code LivingEntity.pushEntities} pushes at most {@link
 * #maxEntityCollisions()} entities per tick; its cramming check still counts
 * every overlapping entity.
 *
 * <p>Both are off in mode {@code off} ({@code MultiForgeConfig.effectiveActivation})
 * and in the vanilla-parity gate ({@code DeterminismRun}).
 */
@ApiStatus.Internal
public final class EntityActivation {
    /** Entity types that always tick; ships with the bosses and the warden. */
    public static final TagKey<EntityType<?>> EXEMPT_TAG = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("multiforge", "activation_exempt"));

    private static final int VERTICAL_RANGE = 256;
    private static final int YOUNG_TICKS = 20;
    private static final long HURT_RECENTLY_TICKS = 100L;

    private static volatile ActivationConfig config = ActivationConfig.DEFAULTS.withActivation(false).withMaxEntityCollisions(0);
    private static volatile boolean enabled;
    private static volatile int maxEntityCollisions;
    private static volatile Set<EntityType<?>> exemptTypes = Set.of();
    private static volatile List<TagKey<EntityType<?>>> exemptTags = List.of();

    private static final InstanceRegistry<ServerLevel, double[]> PLAYERS = InstanceRegistry.weak();
    private static final ThreadLocal<Context> CONTEXT = ThreadLocal.withInitial(Context::new);

    // GameTests only: entities evaluated even while activation is off, a
    // pretend player position per subject, and per-subject [full, skipped] tick
    // counts. Scoped to the subjects so concurrent tests do not see each other.
    private static volatile boolean testing;
    private static final Set<Entity> TEST_SUBJECTS = ConcurrentHashMap.newKeySet();
    private static final Map<Entity, Vec3> TEST_VIEWERS = new ConcurrentHashMap<>();
    private static final Map<Entity, AtomicIntegerArray> TEST_COUNTS = new ConcurrentHashMap<>();

    private EntityActivation() {}

    /** The region entity pass in progress on this thread. */
    private static final class Context {
        @Nullable
        ServerLevel level;
        double[] players = new double[0];
        long gameTime;
        int interval;
        long skipped;
    }

    /**
     * Apply the effective settings ({@code MultiForgeConfig.effectiveActivation},
     * already off in mode {@code off}). Resolves the exempt list against the
     * entity-type registry; an unknown id is logged and ignored.
     */
    public static void configure(ActivationConfig effective) {
        Set<EntityType<?>> types = new HashSet<>();
        List<TagKey<EntityType<?>>> tags = new ArrayList<>();
        for (String raw : effective.exempt()) {
            String id = raw.trim();
            boolean tag = id.startsWith("#");
            ResourceLocation key = ResourceLocation.tryParse(tag ? id.substring(1) : id);
            if (key == null) {
                ViolationLogger.warn("entities.activationExempt", "ignoring malformed activationExempt entry " + raw);
            } else if (tag) {
                tags.add(TagKey.create(Registries.ENTITY_TYPE, key));
            } else if (BuiltInRegistries.ENTITY_TYPE.containsKey(key)) {
                types.add(BuiltInRegistries.ENTITY_TYPE.get(key));
            } else {
                ViolationLogger.warn("entities.activationExempt", "ignoring unknown entity type in activationExempt: " + raw);
            }
        }
        exemptTypes = Set.copyOf(types);
        exemptTags = List.copyOf(tags);
        config = effective;
        maxEntityCollisions = effective.maxEntityCollisions();
        enabled = effective.activation();
    }

    /** Whether activation range is on. */
    public static boolean enabled() {
        return enabled;
    }

    /** The push cap for {@code LivingEntity.pushEntities}: entities pushed per tick, 0 = no cap. */
    public static int maxEntityCollisions() {
        return maxEntityCollisions;
    }

    /**
     * Server thread, before {@code level}'s regions run: record where its
     * players are (spectators do not wake mobs).
     */
    public static void snapshotPlayers(ServerLevel level) {
        if (!enabled && !testing) return;
        List<ServerPlayer> players = level.players();
        double[] xyz = new double[players.size() * 3];
        int n = 0;
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            if (p.isSpectator()) continue;
            xyz[n++] = p.getX();
            xyz[n++] = p.getY();
            xyz[n++] = p.getZ();
        }
        PLAYERS.register(level, n == xyz.length ? xyz : java.util.Arrays.copyOf(xyz, n));
    }

    /**
     * The ticking thread, before a region's entity pass: throttle inactive mobs
     * of {@code level} until {@link #endRegion}. {@code lastTickNanos} is the
     * region's own time in its previous tick (load shedding).
     */
    public static void beginRegion(ServerLevel level, long lastTickNanos) {
        if (!enabled && !testing) return;
        double[] players = PLAYERS.of(level).orElse(null);
        if (players == null) return;
        Context ctx = CONTEXT.get();
        ctx.level = level;
        ctx.players = players;
        ctx.gameTime = level.getGameTime();
        ctx.interval = ActivationConfig.shedInterval(config.wakeInterval(), lastTickNanos);
        ctx.skipped = 0;
    }

    /** The ticking thread, after the region's entity pass. */
    public static void endRegion() {
        Context ctx = CONTEXT.get();
        if (ctx.level == null) return;
        ctx.level = null;
        if (ctx.skipped > 0) ProbeRegistry.add("entity.activation.skipped", ctx.skipped);
        ctx.skipped = 0;
    }

    /**
     * Head of {@code ServerLevel.tickNonPassenger}: whether {@code entity}'s
     * tick is replaced by an inactive tick this time (which this call ran).
     */
    public static boolean skipTick(ServerLevel level, Entity entity) {
        if (!enabled && !testing) return false;
        if (!(entity instanceof Mob mob)) return false;
        Context ctx = CONTEXT.get();
        if (ctx.level != level) return false;
        boolean subject = testing && TEST_SUBJECTS.contains(entity);
        if (!enabled && !subject) return false;
        boolean skip = !ActivationConfig.isWakeTick(ctx.gameTime, mob.getId(), ctx.interval)
                && !isActive(ctx, mob, subject ? TEST_VIEWERS.get(entity) : null);
        if (subject) TEST_COUNTS.computeIfAbsent(entity, e -> new AtomicIntegerArray(2)).incrementAndGet(skip ? 1 : 0);
        if (!skip) return false;
        inactiveTick(mob);
        ctx.skipped++;
        return true;
    }

    private static boolean isActive(Context ctx, Mob mob, @Nullable Vec3 testViewer) {
        int range = rangeFor(mob);
        if (range <= 0) return true; // a range of 0 turns throttling off for the category
        double x = mob.getX();
        double y = mob.getY();
        double z = mob.getZ();
        double[] p = ctx.players;
        for (int i = 0; i < p.length; i += 3) {
            if (near(p[i], p[i + 1], p[i + 2], x, y, z, range)) return true;
        }
        if (testViewer != null && near(testViewer.x, testViewer.y, testViewer.z, x, y, z, range)) return true;
        return isImmune(mob, ctx.gameTime);
    }

    private static boolean near(double px, double py, double pz, double x, double y, double z, int range) {
        return Math.abs(px - x) <= range && Math.abs(pz - z) <= range && Math.abs(py - y) <= VERTICAL_RANGE;
    }

    private static int rangeFor(Mob mob) {
        ActivationConfig c = config;
        if (mob instanceof Raider) return c.raiderRange();
        if (mob instanceof AbstractVillager) return c.villagerRange();
        if (mob instanceof FlyingMob) return c.flyingRange();
        return switch (mob.getType().getCategory()) {
            case MONSTER -> c.monsterRange();
            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> c.waterRange();
            case AMBIENT -> c.ambientRange();
            default -> c.animalRange();
        };
    }

    private static boolean isWaterMob(Mob mob) {
        return switch (mob.getType().getCategory()) {
            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> true;
            default -> false;
        };
    }

    private static boolean flies(Mob mob) {
        return mob instanceof FlyingMob || mob instanceof FlyingAnimal || mob instanceof AmbientCreature;
    }

    /**
     * Mobs that always tick, near a player or not: anything busy (riding or
     * ridden, leashed, targeting, hurt in the last 5 s, in love, under 1 s
     * old), bosses, multipart and always-ticking entities, the exempt types,
     * and the farm cases: a land mob with AI falling (a drop chute) or in water
     * (a stream).
     */
    @VisibleForTesting
    public static boolean isImmune(Mob mob, long gameTime) {
        if (mob.tickCount < YOUNG_TICKS) return true;
        if (mob.isPassenger() || mob.isVehicle()) return true;
        if (mob.isLeashed()) return true;
        if (mob.getTarget() != null) return true;
        if (gameTime - mob.mfLastDamageStamp() < HURT_RECENTLY_TICKS) return true;
        if (mob instanceof Animal animal && animal.isInLove()) return true;
        if (mob.isMultipartEntity() || mob.isAlwaysTicking()) return true;
        if (isExemptType(mob.getType())) return true;
        // A mob without AI does not move itself, so it is no farm's concern.
        if (!isWaterMob(mob) && !mob.isNoAi()) {
            if (mob.isInWater()) return true;
            if (!mob.onGround() && !mob.isNoGravity() && !flies(mob)) return true;
        }
        return false;
    }

    /** Whether {@code type} always ticks: a boss, the exempt tag, or the config's exempt list. */
    public static boolean isExemptType(EntityType<?> type) {
        if (type.is(Tags.EntityTypes.BOSSES) || type.is(EXEMPT_TAG) || exemptTypes.contains(type)) return true;
        for (TagKey<EntityType<?>> tag : exemptTags) {
            if (type.is(tag)) return true;
        }
        return false;
    }

    /**
     * What an inactive mob does instead of its tick: what {@code
     * tickNonPassenger} does before calling {@code tick()}, plus the counters
     * that must keep running so despawning and growing up keep their pace.
     */
    private static void inactiveTick(Mob mob) {
        mob.setOldPosAndRot();
        mob.tickCount++;
        if (!mob.isNoAi()) mob.setNoActionTime(mob.getNoActionTime() + 1);
        if (mob instanceof AgeableMob ageable && ageable.isAlive()) {
            int age = ageable.getAge();
            if (age < 0) ageable.setAge(age + 1);
            else if (age > 0) ageable.setAge(age - 1);
        }
    }

    // ---- GameTest hooks ----------------------------------------------------

    /** Tests: evaluate {@code entity} for activation even while it is off, and count its ticks. */
    @VisibleForTesting
    public static void testSubject(Entity entity) {
        TEST_SUBJECTS.add(entity);
        testing = true;
    }

    /** Tests: treat {@code pos} as a player position for {@link #testSubject} {@code subject}. */
    @VisibleForTesting
    public static void testViewer(Entity subject, Vec3 pos) {
        TEST_VIEWERS.put(subject, pos);
    }

    /** Tests: [full ticks, inactive ticks] of a {@link #testSubject} so far. */
    @VisibleForTesting
    public static int[] testCounts(Entity entity) {
        AtomicIntegerArray a = TEST_COUNTS.get(entity);
        return a == null ? new int[2] : new int[] { a.get(0), a.get(1) };
    }

    /** Tests: reset a subject's counts. */
    @VisibleForTesting
    public static void testResetCounts(Entity entity) {
        TEST_COUNTS.remove(entity);
    }

    /** Tests: forget {@code subjects}, their viewers and counts. */
    @VisibleForTesting
    public static void testRelease(List<? extends Entity> subjects) {
        for (Entity e : subjects) {
            TEST_SUBJECTS.remove(e);
            TEST_VIEWERS.remove(e);
            TEST_COUNTS.remove(e);
        }
        testing = !TEST_SUBJECTS.isEmpty();
    }
}
