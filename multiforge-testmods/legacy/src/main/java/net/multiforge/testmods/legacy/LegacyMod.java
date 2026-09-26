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
package net.multiforge.testmods.legacy;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Fixture: a mod written the way most pre-MultiForge mods are — per-tick
 * state in a plain {@link HashMap} and {@code long}, no synchronisation,
 * assuming it is only ever called from the server thread.
 *
 * <p>It declares {@code multiforge_safety = "legacy"} in its mods.toml, so
 * MultiForge runs all of its listeners on the serial lane: one at a time, on
 * the server thread, even for entity ticks that happen on region workers.
 * {@code /mftest_legacy stats} reports how many entity ticks it counted and
 * how many reached it on any other thread (should be none) — on stock
 * NeoForge trivially, on MultiForge because of the lane.
 */
@Mod("mftest_legacy")
public final class LegacyMod {
    private static final Map<EntityType<?>, Integer> TICKS_BY_TYPE = new HashMap<>();
    private static long total;
    private static long offServerThread;

    public LegacyMod() {
        NeoForge.EVENT_BUS.addListener(LegacyMod::onEntityTick);
        NeoForge.EVENT_BUS.addListener(LegacyMod::onRegisterCommands);
    }

    private static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) offServerThread++;
        TICKS_BY_TYPE.merge(event.getEntity().getType(), 1, Integer::sum);
        total++;
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("mftest_legacy")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("stats").executes(ctx -> {
                            int sum = TICKS_BY_TYPE.values().stream()
                                    .mapToInt(Integer::intValue)
                                    .sum();
                            ctx.getSource()
                                    .sendSuccess(
                                            () -> Component.literal("ticks=" + total + " off_server_thread="
                                                    + offServerThread + " consistent=" + (sum == total)),
                                            false);
                            return 1;
                        })));
    }
}
