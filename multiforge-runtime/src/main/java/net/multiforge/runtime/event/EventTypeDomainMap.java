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

    /** The entry for exactly {@code eventClassName}, no hierarchy walk (tests: the NeoForge classes are not on their classpath). */
    static Optional<DispatchDomainKind> entryFor(String eventClassName) {
        ensureInitialized();
        return Optional.ofNullable(MAP.get(eventClassName));
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
        MAP.put(eventClassName, kind);
        RoutingEpoch.bump();
    }

    /** Test-only: drops every entry, including the lazily-built defaults, so the next {@link #lookup} repopulates them. */
    static void resetForTesting() {
        synchronized (INIT_LOCK) {
            MAP.clear();
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
}
