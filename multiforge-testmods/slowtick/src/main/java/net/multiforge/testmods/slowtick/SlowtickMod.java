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
package net.multiforge.testmods.slowtick;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Fixture: slow server ticks on demand, for the watchdog checks.
 * {@code /mftest_slowtick run <ticks> <ms>} makes each of the next {@code
 * ticks} server ticks sleep {@code ms} on the server thread (at the end of the
 * tick). {@code /mftest_slowtick stats} prints {@code remaining}, {@code
 * slow_ticks} and {@code slept_ms}.
 *
 * <p>MultiForge's watchdog measures how long the current tick has run, not how
 * far the server is behind: 120 ticks of 1 s each must not stop the server
 * (Vanilla's formula reads the accumulated lag as one 60 s tick), and one 70 s
 * tick must still stop it.
 */
@Mod("mftest_slowtick")
public final class SlowtickMod {
    private static final AtomicInteger REMAINING = new AtomicInteger();
    private static final AtomicLong SLEEP_MS = new AtomicLong();
    private static final AtomicLong SLOW_TICKS = new AtomicLong();
    private static final AtomicLong SLEPT_MS = new AtomicLong();

    public SlowtickMod() {
        NeoForge.EVENT_BUS.addListener(SlowtickMod::onServerTick);
        NeoForge.EVENT_BUS.addListener(SlowtickMod::onRegisterCommands);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (REMAINING.get() <= 0) return;
        REMAINING.decrementAndGet();
        long ms = SLEEP_MS.get();
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        SLOW_TICKS.incrementAndGet();
        SLEPT_MS.addAndGet(ms);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("mftest_slowtick")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("run")
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("ms", IntegerArgumentType.integer(0))
                                                .executes(ctx -> {
                                                    SLEEP_MS.set(IntegerArgumentType.getInteger(ctx, "ms"));
                                                    REMAINING.set(IntegerArgumentType.getInteger(ctx, "ticks"));
                                                    ctx.getSource()
                                                            .sendSuccess(
                                                                    () -> Component.literal("slowtick armed"), false);
                                                    return 1;
                                                }))))
                        .then(Commands.literal("stats").executes(ctx -> {
                            ctx.getSource()
                                    .sendSuccess(
                                            () -> Component.literal("remaining=" + REMAINING.get() + " slow_ticks="
                                                    + SLOW_TICKS.get() + " slept_ms=" + SLEPT_MS.get()),
                                            false);
                            return 1;
                        })));
    }
}
