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
package net.multiforge.neoforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.multiforge.runtime.chunk.InstanceRegistry;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.config.MultiForgeConfigStore;
import net.multiforge.runtime.region.pin.RegionPinManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-scoped shared state that both {@link
 * net.multiforge.neoforge.commands.MultiForgeCommandBinder} and
 * {@link net.multiforge.neoforge.debug.DebugChannelServer} consult so
 * that the {@code /multiforge region pin ...} command and the
 * {@code multiforge:debug/v1} channel's {@code PinListEmitter} share
 * one {@link RegionPinManager} instance.
 *
 * <p>Pre-v1.3.16 each side loaded its own instance from disk, so a
 * pin added by the command was invisible to the debug channel until
 * a server restart. The user's live testing surfaced this — pinned
 * chunks never showed up as boxes on the client. See v1.3.16
 * CHANGELOG for the full story.
 *
 * <p>Uses {@link InstanceRegistry} (CLAUDE.md region-tick conventions),
 * weakly keyed by the {@link MinecraftServer} so a reused GameTestServer JVM releases the
 * old server's pin manager when the server itself gets GC'd. The
 * {@code ServerStoppingEvent} handler in {@link
 * net.multiforge.neoforge.debug.DebugChannelServer} explicitly clears
 * on stop for the common case where the server is not yet
 * garbage-collectable.
 */
public final class MultiForgeServerState {
    private static final Logger LOGGER = LoggerFactory.getLogger("multiforge.serverstate");
    private static final InstanceRegistry<MinecraftServer, RegionPinManager> PINS = InstanceRegistry.weak();
    private static final InstanceRegistry<MinecraftServer, MultiForgeConfigStore> CONFIGS = InstanceRegistry.weak();

    private MultiForgeServerState() {}

    /**
     * Return the {@link RegionPinManager} for {@code server}, loading
     * it from {@code <serverDir>/config/multiforge-region-pins.toml}
     * on first call. Later callers get the same instance so mutations
     * from one caller are visible to another.
     */
    public static synchronized RegionPinManager pinManagerFor(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        RegionPinManager cached = PINS.of(server).orElse(null);
        if (cached != null) {
            return cached;
        }
        Path configDir = server.getServerDirectory().toAbsolutePath().resolve("config");
        Path pinsFile = configDir.resolve("multiforge-region-pins.toml");
        // Through v1.5 the same TOML content was written under a .json name.
        Path legacyFile = configDir.resolve("multiforge-region-pins.json");
        RegionPinManager loaded;
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(pinsFile) && Files.isRegularFile(legacyFile)) Files.move(legacyFile, pinsFile);
            loaded = RegionPinManager.load(pinsFile);
        } catch (IOException e) {
            LOGGER.warn("failed to load {} — using empty pin manager: {}", pinsFile, e.getMessage());
            loaded = new RegionPinManager(pinsFile);
        }
        PINS.register(server, loaded);
        return loaded;
    }

    /**
     * Return the {@link MultiForgeConfigStore} for {@code server}, loading
     * {@code <serverDir>/config/multiforge-server.toml} on first call (and
     * writing defaults if the file does not exist). Shared by the runtime
     * install in {@code ServerLifecycleHooks} and the {@code /multiforge
     * config} command, so both see the same snapshot.
     */
    public static synchronized MultiForgeConfigStore configStoreFor(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        MultiForgeConfigStore cached = CONFIGS.of(server).orElse(null);
        if (cached != null) {
            return cached;
        }
        Path configFile = server.getServerDirectory()
                .toAbsolutePath()
                .resolve("config")
                .resolve("multiforge-server.toml");
        MultiForgeConfigStore loaded;
        try {
            Files.createDirectories(configFile.getParent());
            loaded = MultiForgeConfigStore.load(configFile);
        } catch (IOException e) {
            LOGGER.warn("failed to load {} — using defaults: {}", configFile, e.getMessage());
            loaded = new MultiForgeConfigStore(configFile, MultiForgeConfig.defaults());
        }
        CONFIGS.register(server, loaded);
        return loaded;
    }

    /**
     * Apply the parts of {@code config} that live in process-wide state:
     * ownership enforcement (strict for {@code mode = "strict"} or {@code
     * policy = "fail"}), the tick watchdog, and the violation-warning budget
     * ({@code policy = "reroute-only"} silences it). {@code mode = "off"} only
     * matters at server start, where it skips installing the runtime.
     *
     * <p>An explicitly set {@code -Dmultiforge.ownership.mode=off|reroute|strict}
     * or {@code -Dmultiforge.regiontick.strict=on} wins over the file, so a
     * regression run can force either without editing the config.
     */
    public static void applyConfig(MultiForgeConfig config) {
        boolean strict = config.effectiveMode() == MultiForgeConfig.Mode.STRICT;
        net.multiforge.runtime.ownership.OwnershipEnforcer.Mode ownership = strict || config.violationPolicy() == MultiForgeConfig.ViolationPolicy.FAIL
                ? net.multiforge.runtime.ownership.OwnershipEnforcer.Mode.STRICT
                : net.multiforge.runtime.ownership.OwnershipEnforcer.Mode.REROUTE;
        String ownershipProp = System.getProperty("multiforge.ownership.mode");
        if (ownershipProp != null && !ownershipProp.isBlank()) {
            ownership = net.multiforge.runtime.ownership.OwnershipEnforcer.parseMode(ownershipProp);
        }
        net.multiforge.runtime.ownership.OwnershipEnforcer.setMode(ownership);
        String strictProp = System.getProperty("multiforge.regiontick.strict", "");
        boolean watchdogStrict = strict || strictProp.equalsIgnoreCase("on") || strictProp.equalsIgnoreCase("true")
                || strictProp.equalsIgnoreCase("strict");
        net.multiforge.runtime.region.RegionTickWatchdog.setMode(watchdogStrict
                ? net.multiforge.runtime.region.RegionTickWatchdog.Mode.STRICT
                : net.multiforge.runtime.region.RegionTickWatchdog.Mode.WARN);
        net.multiforge.runtime.diagnostics.ViolationLogger.configure(
                config.violationPolicy() == MultiForgeConfig.ViolationPolicy.REROUTE_ONLY ? 0L : config.warnPerMin());
    }

    /** The current config snapshot for {@code server} — see {@link #configStoreFor}. */
    public static MultiForgeConfig loadConfig(MinecraftServer server) {
        return configStoreFor(server).get();
    }

    /** Explicit clear-on-stop. The weak keys cover the case we forget. */
    public static synchronized void clear(MinecraftServer server) {
        PINS.unregister(server);
        CONFIGS.unregister(server);
    }
}
