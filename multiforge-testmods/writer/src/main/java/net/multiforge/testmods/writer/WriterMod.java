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
package net.multiforge.testmods.writer;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Fixture: an entity tagged {@code mftest_writer} whose NeoForge persistent
 * data holds a target ({@code tx}, {@code ty}, {@code tz}) sets that block to
 * the next wool colour once a second, from its own tick.
 *
 * <p>The listener is unannotated in a mod with no safety declaration, so on
 * MultiForge it runs on the entity's region worker (hybrid-safe, entity-local
 * event). With the target in another region, every write is a cross-region
 * {@code setBlock}: MultiForge reroutes it to the owning region and returns
 * Vanilla's result. {@code /mftest_writer stats} reports writes and how many
 * of them returned {@code false} (should be none: each changes the block).
 */
@Mod("mftest_writer")
public final class WriterMod {
    private static final Block[] WOOL = {
        Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL, Blocks.LIGHT_BLUE_WOOL,
        Blocks.YELLOW_WOOL, Blocks.LIME_WOOL, Blocks.PINK_WOOL, Blocks.GRAY_WOOL
    };
    private static final AtomicLong WRITES = new AtomicLong();
    private static final AtomicLong FALSE_RETURNS = new AtomicLong();

    public WriterMod() {
        NeoForge.EVENT_BUS.addListener(WriterMod::onEntityTick);
        NeoForge.EVENT_BUS.addListener(WriterMod::onRegisterCommands);
    }

    private static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide() || !entity.getTags().contains("mftest_writer")) return;
        if (entity.tickCount % 20 != 0) return;
        CompoundTag data = entity.getPersistentData();
        if (!data.contains("tx")) return;
        BlockPos target = new BlockPos(data.getInt("tx"), data.getInt("ty"), data.getInt("tz"));
        long n = WRITES.getAndIncrement();
        if (!entity.level().setBlock(target, WOOL[(int) (n % WOOL.length)].defaultBlockState(), Block.UPDATE_ALL)) {
            FALSE_RETURNS.incrementAndGet();
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("mftest_writer")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("stats").executes(ctx -> {
                            ctx.getSource()
                                    .sendSuccess(
                                            () -> Component.literal(
                                                    "writes=" + WRITES.get() + " false_returns=" + FALSE_RETURNS.get()),
                                            false);
                            return 1;
                        })));
    }
}
