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
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
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
 * <p>Follows the {@code InstanceRegistry} pattern (CLAUDE.md M9
 * conventions §6): a {@link WeakHashMap} keyed by the
 * {@link MinecraftServer} so a reused GameTestServer JVM releases the
 * old server's pin manager when the server itself gets GC'd. The
 * {@code ServerStoppingEvent} handler in {@link
 * net.multiforge.neoforge.debug.DebugChannelServer} explicitly clears
 * on stop for the common case where the server is not yet
 * garbage-collectable.
 */
public final class MultiForgeServerState {
    private static final Logger LOGGER = LoggerFactory.getLogger("multiforge.serverstate");
    private static final WeakHashMap<MinecraftServer, RegionPinManager> PINS = new WeakHashMap<>();

    private MultiForgeServerState() {}

    /**
     * Return the {@link RegionPinManager} for {@code server}, loading
     * it from {@code <serverDir>/config/multiforge-region-pins.json}
     * on first call. Later callers get the same instance so mutations
     * from one caller are visible to another.
     */
    public static synchronized RegionPinManager pinManagerFor(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        RegionPinManager cached = PINS.get(server);
        if (cached != null) {
            return cached;
        }
        Path pinsFile = server.getServerDirectory()
                .toAbsolutePath()
                .resolve("config")
                .resolve("multiforge-region-pins.json");
        RegionPinManager loaded;
        try {
            Files.createDirectories(pinsFile.getParent());
            loaded = RegionPinManager.load(pinsFile);
        } catch (IOException e) {
            LOGGER.warn("failed to load {} — using empty pin manager: {}", pinsFile, e.getMessage());
            loaded = new RegionPinManager(pinsFile);
        }
        PINS.put(server, loaded);
        return loaded;
    }

    /** Explicit clear-on-stop. WeakHashMap covers the case we forget. */
    public static synchronized void clear(MinecraftServer server) {
        PINS.remove(server);
    }
}
