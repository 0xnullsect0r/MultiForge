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
package net.multiforge.runtime.event;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.multiforge.api.event.DispatchDomainKind;

/**
 * Static default-domain registry for real NeoForge event types, keyed by
 * fully-qualified class name (the {@link Class#getName()} form — nested
 * event classes such as {@code LevelTickEvent.Pre} appear as {@code
 * "net.neoforged.neoforge.event.tick.LevelTickEvent$Pre"}).
 *
 * <p>This is {@link AnnotationScanner}'s third fallback tier (see that
 * class's Javadoc): when a {@code @SubscribeEvent} method carries neither a
 * method-level nor a class-level {@code @DispatchDomain} annotation,
 * {@code AnnotationScanner} consults this map before falling back to {@link
 * DispatchDomainKind#LEGACY_SERIAL}. It gives every listener for the ~30
 * highest-value NeoForge events (`docs/events.md` §Per-event domain
 * reference) a sensible default; a mod can still override any of them by
 * annotating its own listener method directly.
 *
 * <p><b>MC-free by design</b> — the map is keyed by {@code String} class
 * name, not {@code Class<? extends net.neoforged.bus.api.Event>}, so this
 * type imports neither {@code net.neoforged.*} nor {@code net.minecraft.*}.
 * That keeps it usable from {@code multiforge-runtime}, which does not
 * depend on Minecraft.
 *
 * <p>The default entries are populated lazily, on first {@link
 * #lookup(Class)} call, to keep static class-init cheap. {@link
 * #register(String, DispatchDomainKind)} is a thread-safe extension hook —
 * backed by a {@link ConcurrentHashMap} — for downstream code that wants to
 * add or override entries at runtime.
 */
public final class EventTypeDomainMap {

    private static final ConcurrentMap<String, DispatchDomainKind> MAP = new ConcurrentHashMap<>();

    /**
     * Defaults that hold only for listeners someone read: event class name to
     * the domain and the listener classes it was checked for. A listener of
     * such an event that is not on the list keeps the ordinary default (the
     * serial lane) and is logged once. An entry in {@link #MAP} (a default or
     * an operator's {@code [events]} entry) wins over this.
     */
    private static final ConcurrentMap<String, Audited> AUDITED = new ConcurrentHashMap<>();

    /**
     * Fire-and-forget events a region worker posts without waiting: the post
     * runs on the server thread after the level's regions finished ticking
     * (see {@link SerialLane#defer}).
     */
    private static final Set<String> DEFERRED = ConcurrentHashMap.newKeySet();

    /** Listener classes of audited events already logged as unaudited. */
    private static final Set<String> UNAUDITED_LOGGED = ConcurrentHashMap.newKeySet();

    private record Audited(DispatchDomainKind kind, Set<String> listeners) {}

    /**
     * What {@link #lookup(Class, Class)} decided for one listener.
     *
     * @param kind the default domain, or empty for the serial lane
     * @param audited whether it came from an audited entry that lists the listener
     */
    public record Resolution(Optional<DispatchDomainKind> kind, boolean audited) {}

    /** Guards one-time population of {@link #MAP}'s default entries. */
    private static volatile boolean initialized;

    private static final Object INIT_LOCK = new Object();

    private EventTypeDomainMap() {}

    /**
     * Walks {@code eventType}'s class hierarchy (the class itself, then its
     * superclass, and so on up to but excluding {@link Object}), returning
     * the domain registered for the first class whose {@link
     * Class#getName()} has an entry in this map. Returns {@link
     * Optional#empty()} if no class in the hierarchy has one.
     *
     * <p>The hierarchy walk means a mod's custom subclass of a known event
     * (or, symmetrically, an event class not itself listed but whose
     * superclass is) picks up the ancestor's mapping without needing its
     * own entry.
     */
    public static Optional<DispatchDomainKind> lookup(Class<?> eventType) {
        Objects.requireNonNull(eventType, "eventType");
        ensureInitialized();
        for (Class<?> cls = eventType; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            DispatchDomainKind kind = MAP.get(cls.getName());
            if (kind != null) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /**
     * Like {@link #lookup(Class)}, for one listener: an audited entry applies
     * only when {@code listenerClass} (a lambda's host class for a lambda) is
     * one of the listeners it was checked for.
     */
    public static Resolution lookup(Class<?> eventType, Class<?> listenerClass) {
        Objects.requireNonNull(eventType, "eventType");
        ensureInitialized();
        for (Class<?> cls = eventType; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            String name = cls.getName();
            DispatchDomainKind kind = MAP.get(name);
            if (kind != null) return new Resolution(Optional.of(kind), false);
            Audited audited = AUDITED.get(name);
            if (audited != null) {
                String listener = listenerClass == null ? null : hostName(listenerClass);
                if (listener != null && audited.listeners().contains(listener)) {
                    return new Resolution(Optional.of(audited.kind()), true);
                }
                if (listener != null && UNAUDITED_LOGGED.add(name + "#" + listener)) {
                    net.multiforge.runtime.diagnostics.ViolationLogger.warn(
                            "EventTypeDomainMap",
                            "listener " + listener + " of " + name
                                    + " was not audited; it runs on the serial lane (docs/events.md)");
                }
                return new Resolution(Optional.empty(), false);
            }
        }
        return new Resolution(Optional.empty(), false);
    }

    /** The class a listener belongs to: a lambda's or method reference's host class, else the class itself. */
    static String hostName(Class<?> listenerClass) {
        String name = listenerClass.getName();
        int lambda = name.indexOf("$$Lambda");
        return lambda < 0 ? name : name.substring(0, lambda);
    }

    /**
     * Whether a region worker posts {@code eventType} without waiting, the
     * listeners running on the server thread after the level's regions (see
     * {@link #registerDeferred}).
     */
    public static boolean isDeferred(Class<?> eventType) {
        ensureInitialized();
        // Cached per class, tagged with the RoutingEpoch it was computed at: every
        // change to DEFERRED (registerDeferred, register, reset) bumps the epoch
        // after it, so a stale answer is recomputed on the next call.
        DeferredSlot slot = DEFERRED_CACHE.get(eventType);
        int epoch = RoutingEpoch.current();
        DeferredAnswer cached = slot.answer;
        if (cached != null && cached.epoch() == epoch) return cached.deferred();
        boolean deferred = computeDeferred(eventType);
        slot.answer = new DeferredAnswer(epoch, deferred);
        return deferred;
    }

    private static boolean computeDeferred(Class<?> eventType) {
        if (DEFERRED.isEmpty()) return false;
        for (Class<?> cls = eventType; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            if (DEFERRED.contains(cls.getName())) return true;
        }
        return false;
    }

    /** One class's cached {@link #isDeferred} answer: see {@link #isDeferred}. */
    private static final class DeferredSlot {
        volatile DeferredAnswer answer;
    }

    private record DeferredAnswer(int epoch, boolean deferred) {}

    private static final ClassValue<DeferredSlot> DEFERRED_CACHE = new ClassValue<>() {
        @Override
        protected DeferredSlot computeValue(Class<?> type) {
            return new DeferredSlot();
        }
    };

    /**
     * Posts of {@code eventClassName} from a region worker are deferred: the
     * worker does not wait, and the listeners run on the server thread when the
     * level's regions finished ticking. Only for events whose poster ignores the
     * result and that cannot be cancelled; a cancellable one is posted as usual
     * (see {@code DispatchingEventBus#post}). An operator's {@code [events]}
     * value, or a later {@link #register}, replaces it.
     */
    public static void registerDeferred(String eventClassName) {
        Objects.requireNonNull(eventClassName, "eventClassName");
        ensureInitialized();
        MAP.remove(eventClassName);
        DEFERRED.add(eventClassName);
        RoutingEpoch.bump();
    }

    /**
     * Registers an audited default: {@code eventClassName}'s listeners of the
     * classes {@code listenerClassNames} (a lambda's host class for a lambda)
     * default to {@code kind}; any other listener keeps the serial lane.
     */
    public static void registerAudited(String eventClassName, DispatchDomainKind kind, String... listenerClassNames) {
        Objects.requireNonNull(eventClassName, "eventClassName");
        Objects.requireNonNull(kind, "kind");
        ensureInitialized();
        AUDITED.put(eventClassName, new Audited(kind, Set.of(listenerClassNames)));
        RoutingEpoch.bump();
    }

    /** The entry for exactly {@code eventClassName}, no hierarchy walk (tests: the NeoForge classes are not on their classpath). */
    static Optional<DispatchDomainKind> entryFor(String eventClassName) {
        ensureInitialized();
        return Optional.ofNullable(MAP.get(eventClassName));
    }

    /** The listener classes an audited entry for exactly {@code eventClassName} lists (tests). */
    static Optional<Set<String>> auditedListeners(String eventClassName) {
        ensureInitialized();
        Audited audited = AUDITED.get(eventClassName);
        return audited == null ? Optional.empty() : Optional.of(audited.listeners());
    }

    /** Whether exactly {@code eventClassName} is deferred (tests). */
    static boolean deferredEntry(String eventClassName) {
        ensureInitialized();
        return DEFERRED.contains(eventClassName);
    }

    /**
     * Registers (or overrides) the default domain for the event class named
     * {@code eventClassName}. Extension hook for downstream code — safe to
     * call concurrently with {@link #lookup(Class)} and with other {@link
     * #register} calls.
     */
    public static void register(String eventClassName, DispatchDomainKind kind) {
        Objects.requireNonNull(eventClassName, "eventClassName");
        Objects.requireNonNull(kind, "kind");
        ensureInitialized();
        MAP.put(eventClassName, kind);
        DEFERRED.remove(eventClassName);
        RoutingEpoch.bump();
    }

    /** Test-only: drops every entry, including the lazily-built defaults, so the next {@link #lookup} repopulates them. */
    static void resetForTesting() {
        synchronized (INIT_LOCK) {
            MAP.clear();
            AUDITED.clear();
            DEFERRED.clear();
            UNAUDITED_LOGGED.clear();
            initialized = false;
            RoutingEpoch.bump();
        }
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }
            populateDefaults();
            initialized = true;
        }
    }

    /**
     * The ~30 highest-value NeoForge events from {@code docs/events.md}'s
     * per-event target-domain table. {@code putIfAbsent} so an earlier
     * {@link #register} call (e.g. from a test, or from downstream code
     * that ran before the first {@link #lookup}) is never clobbered by a
     * default.
     */
    private static void populateDefaults() {
        // Tick events.
        put("net.neoforged.neoforge.event.tick.LevelTickEvent$Pre", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.tick.LevelTickEvent$Post", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.tick.ServerTickEvent$Pre", DispatchDomainKind.GLOBAL);
        put("net.neoforged.neoforge.event.tick.ServerTickEvent$Post", DispatchDomainKind.GLOBAL);
        put("net.neoforged.neoforge.event.tick.EntityTickEvent$Pre", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.tick.EntityTickEvent$Post", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.tick.PlayerTickEvent$Pre", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.tick.PlayerTickEvent$Post", DispatchDomainKind.REGION);

        // Entity lifecycle + combat.
        put("net.neoforged.neoforge.event.entity.EntityJoinLevelEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.living.LivingDeathEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.living.LivingDamageEvent$Pre", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.living.LivingDamageEvent$Post", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.living.LivingDropsEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.living.MobSpawnEvent$PositionCheck", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.living.MobSpawnEvent$SpawnPlacementCheck", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.EntityEvent$Size", DispatchDomainKind.REGION);

        // Per-entity events posted from the entity's own tick on its owning region,
        // about that entity alone. These were the most frequent serial-lane events
        // on an ATM10 bench (event.dispatch.serial.event.*): run on the lane, each
        // cost a round trip to the server thread for every living entity, every tick.
        // LivingEntity.baseTick -> CommonHooks.onLivingBreathe: every living entity, every tick.
        put("net.neoforged.neoforge.event.entity.living.LivingBreatheEvent", DispatchDomainKind.REGION);
        // Mob.checkDespawn -> EventHooks.checkMobDespawn: every mob, every tick.
        put("net.neoforged.neoforge.event.entity.living.MobDespawnEvent", DispatchDomainKind.REGION);
        // Mob AI asking whether it may change blocks (EventHooks.canEntityGrief).
        put("net.neoforged.neoforge.event.entity.EntityMobGriefingEvent", DispatchDomainKind.REGION);
        // Target selection in mob AI (CommonHooks.onLivingChangeTarget).
        put("net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent", DispatchDomainKind.REGION);
        // Visibility scaling when a mob looks for a target (CommonHooks.getEntityVisibilityMultiplier).
        put("net.neoforged.neoforge.event.entity.living.LivingEvent$LivingVisibilityEvent", DispatchDomainKind.REGION);
        // Movement of the entity itself (CommonHooks.onLivingFall / onLivingJump).
        put("net.neoforged.neoforge.event.entity.living.LivingFallEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.living.LivingEvent$LivingJumpEvent", DispatchDomainKind.REGION);
        // The entity crossing a section border (CommonHooks.onEntityEnterSection).
        put("net.neoforged.neoforge.event.entity.EntityEvent$EnteringSection", DispatchDomainKind.REGION);
        // A game event at one position, posted by whatever caused it there (CommonHooks.onVanillaGameEvent).
        put("net.neoforged.neoforge.event.VanillaGameEvent", DispatchDomainKind.REGION);

        // The next three were the most frequent serial-lane events left on an ATM10
        // modpack server running the defaults above (~630, ~600 and ~240 per tick).
        // Their listeners in that pack (Apotheosis, Apothic Enchanting, Advanced AE,
        // Ars Elemental, Just Dire Things, Mekanism, Relics, Neo Vitae, Silent Gear,
        // Twilight Forest) were read from their bytecode: they read the entity, its
        // equipment, the damage source, the item stack or the structure at the
        // position, and write only the event, that entity or its items. None writes a
        // static field or a shared collection; Apotheosis guards its re-entry with a
        // ThreadLocal.
        // Entity.isInvulnerableTo -> CommonHooks.isEntityInvulnerableTo: the entity
        // being hurt, checked in its own damage path.
        put("net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent", DispatchDomainKind.REGION);
        // NaturalSpawner -> EventHooks.getPotentialSpawns: one position of a region
        // spawning mobs in its own chunks (per-region spawning).
        put("net.neoforged.neoforge.event.level.LevelEvent$PotentialSpawns", DispatchDomainKind.REGION);
        // EventHooks.getEnchantmentLevelSpecific / getAllEnchantmentLevels: a query on
        // one item stack, posted by whoever holds or uses it.
        put("net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent", DispatchDomainKind.REGION);
        // LivingHealEvent (~220 per tick there) stays serial: two Relics listeners
        // (JellyfishNecklaceItem, MidnightMantleItem) return early unless
        // MinecraftServer.isSameThread(), which is false on a region worker, so on the
        // region their effects would silently stop.

        // Events mods define and post from an entity's own movement, per entity per
        // tick, audited listener by listener (bytecode of relics 0.12.8,
        // reliquified_artifacts 1.0.8, artifacts 13.2.3 with expandability 12.0.0,
        // lionfishapi 3.1, cataclysm 3.33). Every listener reads the posting entity
        // (its flags, Curios items, item data components, attachments) and the block
        // states under its own bounding box, and writes only the event; none writes a
        // static field or calls isSameThread. The one shared structure they reach,
        // relics' CacheHandler.TEMPLATE_CACHE, is a Collections.synchronizedMap. On an
        // ATM10 bench these were ~4,000 serial-lane posts per tick. A listener not
        // listed here (another mod, or a new class after an update) keeps the serial
        // lane and is logged once.
        // Relics BlockStateMixin.getFluidCollisionShape / PlayerMixin: the living
        // entity of an EntityCollisionContext, asking whether it stands on a fluid.
        audited(
                "it.hurts.sskirillss.relics.api.events.utility.FluidCollisionEvent",
                "it.hurts.sskirillss.relics.items.relics.feet.CutGlassBootItem$CommonEvents",
                "it.hurts.shatterbyte.reliquified_artifacts.items.feet.AquaDashersItem$CommonEvents",
                "it.hurts.shatterbyte.reliquified_artifacts.items.feet.StriderShoesItem$CommonEvents");
        // Relics LivingEntityMixin.setBlockFriction: the entity's friction on the block below.
        audited(
                "it.hurts.sskirillss.relics.api.events.utility.LivingSlippingEvent",
                "it.hurts.sskirillss.relics.items.relics.feet.RollerSkateItem$Events",
                "it.hurts.shatterbyte.reliquified_artifacts.items.feet.SteadfastSpikesItem$SteadfastSpikesEvent");
        // Relics EntityMixin.getBlockSpeedFactor: the entity's speed on the block below.
        audited(
                "it.hurts.sskirillss.relics.api.events.utility.EntityBlockSpeedFactorEvent",
                "it.hurts.sskirillss.relics.items.relics.feet.RollerSkateItem$Events");
        // Relics RelicData, while one of the listeners above reads a relic's data: the
        // bearer's rank for that stack.
        audited(
                "it.hurts.sskirillss.relics.api.events.relic.GatherRelicTemplateCacheKeyEvent",
                "it.hurts.sskirillss.relics.handlers.RankHandler");
        // Expandability EventDispatcherImpl (fluid collision of a living entity).
        audited(
                "be.florens.expandability.api.forge.LivingFluidCollisionEvent",
                "artifacts.neoforge.event.ArtifactHooksNeoForge");
        // Lionfishapi EntityMixin.fluidCollision: the entity standing on a fluid.
        audited(
                "com.github.L_Ender.lionfishapi.server.event.StandOnFluidEvent",
                "com.github.L_Ender.cataclysm.event.ServerEventHandler");
        // Xycraft ItemEntityTickMixin -> ItemEntityTickEvent.onTick: every item entity,
        // every tick. The poster discards the result (post; pop; return) and the event
        // cannot be cancelled. Its listener, CollectorBlockEntity.absorbItem, reads a
        // per-level volume map and inserts into a collector that may be in another
        // region, so it cannot run on the region: the post runs on the server thread
        // after the level's regions, when no worker is running.
        DEFERRED.add("tv.soaryn.xycraft.core.event.ItemEntityTickEvent");

        // Player events.
        put("net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent", DispatchDomainKind.GLOBAL);
        put("net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedOutEvent", DispatchDomainKind.GLOBAL);
        put("net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$LeftClickBlock", DispatchDomainKind.REGION);
        put(
                "net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$RightClickBlock",
                DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$RightClickItem", DispatchDomainKind.REGION);

        // Chat / commands.
        put("net.neoforged.neoforge.event.ServerChatEvent", DispatchDomainKind.GLOBAL);
        put("net.neoforged.neoforge.event.CommandEvent", DispatchDomainKind.GLOBAL);

        // Level / chunk lifecycle.
        put("net.neoforged.neoforge.event.level.ChunkEvent$Load", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.ChunkEvent$Unload", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.LevelEvent$Load", DispatchDomainKind.GLOBAL);
        put("net.neoforged.neoforge.event.level.LevelEvent$Unload", DispatchDomainKind.GLOBAL);

        // Explosions + blocks.
        put("net.neoforged.neoforge.event.level.ExplosionEvent$Start", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.ExplosionEvent$Detonate", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.BlockEvent$PortalSpawnEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.BlockEvent$FarmlandTrampleEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.BlockEvent$NeighborNotifyEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.BlockEvent$BreakEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.BlockEvent$EntityPlaceEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.BlockEvent$EntityMultiPlaceEvent", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.block.CropGrowEvent$Pre", DispatchDomainKind.REGION);
        put("net.neoforged.neoforge.event.level.block.CropGrowEvent$Post", DispatchDomainKind.REGION);

        // Server lifecycle.
        put("net.neoforged.neoforge.event.server.ServerAboutToStartEvent", DispatchDomainKind.GLOBAL);
        put("net.neoforged.neoforge.event.server.ServerStartedEvent", DispatchDomainKind.GLOBAL);
        put("net.neoforged.neoforge.event.server.ServerStoppingEvent", DispatchDomainKind.GLOBAL);
    }

    private static void put(String eventClassName, DispatchDomainKind kind) {
        MAP.putIfAbsent(eventClassName, kind);
    }

    private static void audited(String eventClassName, String... listenerClassNames) {
        AUDITED.putIfAbsent(eventClassName, new Audited(DispatchDomainKind.REGION, Set.of(listenerClassNames)));
    }
}
