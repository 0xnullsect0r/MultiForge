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
package net.multiforge.testmods.limbo;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Fixture for the entity-limbo stress run. An entity tagged {@code
 * mftest_limbo} does, from its own tick (on MultiForge, its region's worker),
 * whichever of these its NeoForge persistent data asks for:
 *
 * <ul>
 *   <li>{@code fx}, {@code fz}: every 7 ticks it forces or unforces that
 *       chunk, a ticket change made on a worker (rerouted to the server
 *       thread);</li>
 *   <li>{@code lx}, {@code lz}: every {@code every} ticks (default 2) it loads
 *       the next chunk of the 16x16-chunk area with that corner through {@code
 *       getChunk}, so the worker hands the load to the server thread and
 *       waits, and the server thread pumps chunk work, promotions and
 *       demotions included, while the other regions' workers move their
 *       entities. The chunks have no ticket, so they unload again and the
 *       next lap loads them again.</li>
 * </ul>
 *
 * <p>{@code /mftest_limbo stats} prints {@code toggles}, {@code loads} and
 * {@code load_ms}.
 */
@Mod("mftest_limbo")
public final class LimboMod {
    private static final AtomicLong TOGGLES = new AtomicLong();
    private static final AtomicLong LOADS = new AtomicLong();
    private static final AtomicLong LOAD_NANOS = new AtomicLong();

    public LimboMod() {
        NeoForge.EVENT_BUS.addListener(LimboMod::onEntityTick);
        NeoForge.EVENT_BUS.addListener(LimboMod::onRegisterCommands);
    }

    private static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || !entity.getTags().contains("mftest_limbo")) return;
        CompoundTag data = entity.getPersistentData();
        int tick = entity.tickCount;
        if (data.contains("fx") && tick % 7 == 0) {
            level.setChunkForced(data.getInt("fx"), data.getInt("fz"), (tick / 7) % 2 == 0);
            TOGGLES.incrementAndGet();
        }
        int every = Math.max(1, data.contains("every") ? data.getInt("every") : 2);
        if (data.contains("lx") && tick % every == 0) {
            long n = LOADS.getAndIncrement();
            int i = (int) (n % 256);
            long start = System.nanoTime();
            level.getChunk(data.getInt("lx") + i % 16, data.getInt("lz") + i / 16);
            LOAD_NANOS.addAndGet(System.nanoTime() - start);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("mftest_limbo")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("stats").executes(ctx -> {
                            ctx.getSource()
                                    .sendSuccess(
                                            () -> Component.literal("toggles=" + TOGGLES.get() + " loads=" + LOADS.get()
                                                    + " load_ms=" + LOAD_NANOS.get() / 1_000_000),
                                            false);
                            return 1;
                        })));
    }
}
