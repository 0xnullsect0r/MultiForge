/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common.util;

import com.mojang.authlib.GameProfile;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;

// To be expanded for generic Mod fake players?
public class FakePlayerFactory {
    private static final GameProfile MINECRAFT = new GameProfile(UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77"), "[Minecraft]");
    // Map of all active fake player usernames to their entities
    // MultiForge: block entities on different region workers ask for fake players at the same time.
    private static final Map<FakePlayerKey, FakePlayer> fakePlayers = new ConcurrentHashMap<>();

    private record FakePlayerKey(ServerLevel level, GameProfile username) {}

    public static FakePlayer getMinecraft(ServerLevel level) {
        return get(level, MINECRAFT);
    }

    /**
     * Get a fake player with a given username,
     * Mods should either hold weak references to the return value, or listen for a
     * WorldEvent.Unload and kill all references to prevent worlds staying in memory,
     * or call this function every time and let Forge take care of the cleanup.
     */
    public static FakePlayer get(ServerLevel level, GameProfile username) {
        FakePlayerKey key = new FakePlayerKey(level, username);
        FakePlayer existing = fakePlayers.get(key);
        if (existing != null) return existing;
        // Built outside the map: a constructor can run mod code, which must not hold a map lock.
        FakePlayer created = new FakePlayer(level, username);
        existing = fakePlayers.putIfAbsent(key, created);
        return existing != null ? existing : created;
    }

    public static void unloadLevel(ServerLevel level) {
        fakePlayers.entrySet().removeIf(entry -> entry.getValue().level() == level);
    }
}
