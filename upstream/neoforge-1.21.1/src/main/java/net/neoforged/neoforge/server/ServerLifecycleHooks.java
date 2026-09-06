/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.LogicalSidedProvider;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.StructureModifier;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.mixins.MappedRegistryAccessor;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.NeoForgeRegistries.Keys;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.Nullable;

public class ServerLifecycleHooks {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Marker SERVERHOOKS = MarkerManager.getMarker("SERVERHOOKS");
    private static final LevelResource SERVERCONFIG = new LevelResource("serverconfig");
    @Nullable
    private static volatile CountDownLatch exitLatch = null;
    @Nullable
    private static MinecraftServer currentServer;

    private static Path getServerConfigPath(final MinecraftServer server) {
        final Path serverConfig = server.getWorldPath(SERVERCONFIG);
        if (!Files.isDirectory(serverConfig)) {
            try {
                Files.createDirectories(serverConfig);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        final Path explanation = serverConfig.resolve("readme.txt");
        if (!Files.exists(explanation)) {
            try {
                Files.writeString(explanation, """
                        Any server configs put in this folder will override the corresponding server config from <instance path>/config/<config path>.
                        If the config being transferred is in a subfolder of the base config folder make sure to include that folder here in the path to the file you are overwriting.
                        For example if you are overwriting a config with the path <instance path>/config/ExampleMod/config-server.toml, you would need to put it in serverconfig/ExampleMod/config-server.toml
                        """, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return serverConfig;
    }

    public static void handleServerAboutToStart(final MinecraftServer server) {
        // MultiForge fork addition: this method runs on the dedicated
        // server's own "Server thread" (called from
        // DedicatedServer.initServer(), before loadLevel()), so this is
        // the earliest safe point to record which thread is the
        // legitimate single-threaded tick executor for
        // net.multiforge.runtime.ownership.OwnershipEnforcer's ownership
        // guard (multiforge-patches/01-ownership/) — without touching
        // MinecraftServer.runServer itself. The reroute target is bound
        // to the server's own executor as an interim measure until M2
        // wires a real per-region RegionizedTaskQueue (see
        // docs/blueprint.md M7/M8).
        net.multiforge.runtime.ownership.OwnershipEnforcer.bindTickThread(Thread.currentThread());
        // Wrap server::execute in a RejectedExecutionException-catching
        // adapter so the reroute target is no-throw even after server
        // shutdown. /67 round-2 finding: the fundamental race (a caller
        // that already loaded the old reroute-target reference into a
        // local variable) can't be closed by any swap protocol —
        // volatile reads don't invalidate cached locals. But making the
        // target no-throw makes the race benign: worst case the
        // deferred mutation runs on the shutdown-executor and gets
        // silently rejected (with a rate-limited warn), instead of
        // crashing the caller with RejectedExecutionException.
        net.multiforge.runtime.ownership.OwnershipEnforcer.bindRerouteTarget(runnable -> {
            try {
                server.execute(runnable);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                net.multiforge.runtime.diagnostics.ViolationLogger.warn(
                        "OwnershipEnforcer.reroute",
                        "reroute target rejected — server executor is shut down; mutation dropped");
            }
        });

        // MultiForge M8 sub-step 4: bring up the process-wide regionized
        // runtime. The tick body is a no-op for now — the M8 patches
        // that decompose ServerLevel.tick into per-region phases
        // (multiforge-patches/02-region-tick/ + /03-world-data/) will
        // wire a real PhasedRegionTickBody in a follow-up. Installing
        // early here means every subsequent runtime consumer
        // (RegionizedData slots, per-world regionizers) has a live host
        // to reach for.
        try {
            net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime.install(
                    net.multiforge.runtime.config.MultiForgeConfig.defaults(),
                    region -> {});
        } catch (net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime.AlreadyInstalledException already) {
            // Test harnesses (GameTestServer) may install once per JVM and reuse
            // across successive server instances — that's fine, keep going.
        } catch (IllegalStateException foreign) {
            // /67 round-4 fix (1.4): the pre-fix catch swallowed BOTH the
            // benign "already installed same instance" case AND the "foreign
            // SchedulerHost bound to ServerDomains — refuse to overwrite"
            // case, leaving MultiForgeRegionizedRuntime.current() null for
            // the whole server lifetime with no operator warning. Now the
            // benign case has its own subclass (AlreadyInstalledException,
            // caught above) and every other IllegalStateException gets
            // logged loudly + rethrown so a rogue ServiceLoader binding
            // does not silently degrade MultiForge to a no-op.
            net.multiforge.runtime.diagnostics.ViolationLogger.warn(
                    "MultiForgeRegionizedRuntime.install",
                    "install rejected — a non-MultiForge SchedulerHost is bound to ServerDomains: "
                            + foreign.getMessage());
            throw foreign;
        }
        // M9 Phase 5 wave B: wire the real chunk-payload serializer now
        // that install() above has either freshly installed a host or
        // confirmed one is already installed (the AlreadyInstalledException
        // reuse path — same JVM, successive GameTestServer instances).
        // MultiForgeRegionizedRuntime.current() is guaranteed non-null
        // here; the only path that leaves it null (the foreign-host
        // IllegalStateException above) already rethrew and never reaches
        // this line. setChunkSerializer is idempotent and volatile-backed,
        // so re-registering on the reuse path is harmless.
        //
        // This closes the last functional gap in the FLUSH_OUTBOUND
        // autosave wiring: without it, AutoSaveRunner keeps writing
        // byte[0] payloads to the journal (durable but content-free),
        // which is what made the Phase 7.2/7.3 determinism runs unable
        // to validate against real world saves.
        net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost mfHost =
                net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime.current();
        if (mfHost != null) {
            mfHost.setChunkSerializer(net.multiforge.neoforge.io.RegionChunkSerializer::serializeForJournal);
        }
        // M8 sub-step 6a: install the ChunkEvent.Load/Unload listeners
        // that keep the regionizer in sync with Vanilla-loaded chunks.
        // Idempotent per JVM.
        net.multiforge.neoforge.RegionizedChunkLifecycle.installOnEventBus();

        currentServer = server;
        // on the dedi server we need to force the stuff to setup properly
        LogicalSidedProvider.setServer(() -> server);
        ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.SERVER, FMLPaths.CONFIGDIR.get(), getServerConfigPath(server));
        runModifiers(server);
        NeoForge.EVENT_BUS.post(new ServerAboutToStartEvent(server));
    }

    public static void handleServerStarting(final MinecraftServer server) {
        if (FMLEnvironment.dist.isDedicatedServer()) {
            LanguageHook.loadModLanguages(server);
            // GameTestServer requires the gametests to be registered earlier, so it is done in main and should not be done twice.
            if (!(server instanceof GameTestServer))
                GameTestHooks.registerGametests();
        }
        PermissionAPI.initializePermissionAPI();
        NeoForge.EVENT_BUS.post(new ServerStartingEvent(server));
    }

    public static void handleServerStarted(final MinecraftServer server) {
        NeoForge.EVENT_BUS.post(new ServerStartedEvent(server));
    }

    public static void handleServerStopping(final MinecraftServer server) {
        NeoForge.EVENT_BUS.post(new ServerStoppingEvent(server));
    }

    public static void expectServerStopped() {
        exitLatch = new CountDownLatch(1);
    }

    public static void handleServerStopped(final MinecraftServer server) {
        // MultiForge M8 sub-step 4: tear down the regionized runtime.
        // Called from every server-stop path, including the dedi
        // GameTestServer between test runs, so a fresh install can
        // happen next boot.
        try {
            net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime.shutdown();
        } catch (Throwable t) {
            // Never let a runtime-shutdown hiccup prevent normal server-stop cleanup.
            org.slf4j.LoggerFactory.getLogger("multiforge.lifecycle")
                    .warn("MultiForge regionized runtime shutdown threw; continuing normal shutdown", t);
        }

        if (!server.isDedicatedServer()) RegistryManager.revertToFrozen();
        NeoForge.EVENT_BUS.post(new ServerStoppedEvent(server));
        currentServer = null;
        LogicalSidedProvider.setServer(null);
        CountDownLatch latch = exitLatch;

        if (latch != null) {
            latch.countDown();
            exitLatch = null;
        }
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Nullable
    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }

    public static void handleExit(int retVal) {
        System.exit(retVal);
    }

    private static <T> void ensureProperSync(boolean modified, Holder.Reference<T> holder, Registry<T> registry) {
        if (modified) {
            // If the object's networked data has been modified, we force it to sync by removing its original KnownPack info.
            Optional<RegistrationInfo> originalInfo = registry.registrationInfo(holder.key());
            originalInfo.ifPresent(info -> {
                RegistrationInfo newInfo = new RegistrationInfo(Optional.empty(), info.lifecycle());
                //noinspection unchecked
                ((MappedRegistryAccessor<T>) registry).neoforge$getRegistrationInfos().put(holder.key(), newInfo);
            });
        }
    }

    private static void runModifiers(final MinecraftServer server) {
        final RegistryAccess registries = server.registryAccess();

        // The order of holders() is the order modifiers were loaded in.
        final List<BiomeModifier> biomeModifiers = registries.registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS)
                .holders()
                .map(Holder::value)
                .toList();
        final List<StructureModifier> structureModifiers = registries.registryOrThrow(Keys.STRUCTURE_MODIFIERS)
                .holders()
                .map(Holder::value)
                .toList();

        final Set<EntityType<?>> entitiesWithoutPlacements = new HashSet<>();

        // Apply sorted biome modifiers to each biome.
        final var biomeRegistry = registries.registryOrThrow(Registries.BIOME);
        biomeRegistry.holders().forEach(biomeHolder -> {
            final Biome biome = biomeHolder.value();
            ensureProperSync(
                    biome.modifiableBiomeInfo()
                            .applyBiomeModifiers(biomeHolder, biomeModifiers, registries),
                    biomeHolder,
                    biomeRegistry);

            final MobSpawnSettings mobSettings = biome.getMobSettings();
            mobSettings.getSpawnerTypes().forEach(category -> {
                mobSettings.getMobs(category).unwrap().forEach(data -> {
                    if (SpawnPlacements.hasPlacement(data.type)) return;
                    entitiesWithoutPlacements.add(data.type);
                });
            });

            for (MobCategory mobCategory : mobSettings.getSpawnerTypes()) {
                for (MobSpawnSettings.SpawnerData spawnerData : mobSettings.getMobs(mobCategory).unwrap()) {
                    if (spawnerData.type.getCategory() != mobCategory) {
                        // Ignore vanilla bugged entries to reduce unneeded logging. See https://bugs.mojang.com/browse/MC-1788 for the Ocelot/Jungle vanilla bug.
                        boolean isVanillaBug = spawnerData.type == EntityType.OCELOT && (biomeHolder.is(Biomes.JUNGLE) || biomeHolder.is(Biomes.BAMBOO_JUNGLE));
                        if (!isVanillaBug) {
                            LOGGER.warn("Detected {} that was registered with {} mob category but was added under {} mob category for {} biome! " +
                                    "Mobs should be added to biomes under the same mob category that the mob was registered as to prevent mob cap spawning issues.",
                                    BuiltInRegistries.ENTITY_TYPE.getKey(spawnerData.type),
                                    spawnerData.type.getCategory(),
                                    mobCategory,
                                    biomeHolder.getKey().location());
                        }
                    }
                }
            }
        });
        // Rebuild the indexed feature list
        registries.registryOrThrow(Registries.LEVEL_STEM).forEach(levelStem -> {
            levelStem.generator().refreshFeaturesPerStep();
        });

        // Apply sorted structure modifiers to each structure.
        registries.registryOrThrow(Registries.STRUCTURE).holders().forEach(structureHolder -> {
            structureHolder.value().modifiableStructureInfo().applyStructureModifiers(structureHolder, structureModifiers);
        });

        if (!entitiesWithoutPlacements.isEmpty() && !FMLLoader.isProduction()) {
            LOGGER.error("The following entities have not registered to the RegisterSpawnPlacementsEvent, but a spawn entry was found. This will mean that the entity doesn't have restrictions on its spawn location, please register a spawn placement for the entity, you can register with NO_RESTRICTIONS if you don't want any restrictions."
                    + entitiesWithoutPlacements.stream().map(EntityType::getKey).map(ResourceLocation::toString).collect(Collectors.joining("\n\t - ", "\n\t - ", "")));
        }
    }
}
