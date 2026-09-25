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
package net.multiforge.testfixtures;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * Behavioural GameTests for the barrier tick model: with the regionized
 * runtime installed, the work regions own — scheduled block ticks, entity
 * ticking and block-entity ticking — must actually happen, exactly as it
 * would in Vanilla. Each failure message carries the region state of the
 * test's chunk so a regression points at its cause.
 */
@ForEachTest(groups = "multiforge.region-tick")
public class RegionTickBehaviourTests {

    /** Region/holder diagnostics for the chunk containing relative position {@code rel}. */
    static String describe(GameTestHelper helper, BlockPos rel) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return "[runtime not installed]";
        BlockPos abs = helper.absolutePos(rel);
        WorldRef world = helper.getLevel().mfWorldRef();
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) return "[no regionizer for " + world.dimensionId() + "]";
        Region region = regionizer.regionAtChunk(abs.getX() >> 4, abs.getZ() >> 4);
        ChunkHolderManager manager = host.chunkManagerForOrNull(world);
        net.multiforge.api.world.ChunkPos chunk = new net.multiforge.api.world.ChunkPos(abs.getX() >> 4, abs.getZ() >> 4);
        return "[region=" + (region == null ? "none" : region.id() + " state=" + region.state()
                + " ownedChunks=" + region.ownedChunkSnapshot().size()
                + " holdersOwned=" + (manager == null ? "no-manager" : manager.holdersOwnedBy(region.id()).size())
                + " avgMspt=" + (host.scheduler().mspt(region) == null ? "unregistered" : host.scheduler().mspt(region).averageMillis()))
                + " holderAtChunk=" + (manager != null && manager.holderAt(chunk) != null)
                + " regionsInWorld=" + regionizer.regions().size() + "]";
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "A scheduled block tick fires in region mode: a repeater powered by a redstone",
            "block schedules its own tick, and the lamp it feeds lights when that tick runs."
    })
    static void scheduledBlockTickFires(final DynamicTest test) {
        test.onGameTest(helper -> {
            BlockPos lamp = new BlockPos(1, 1, 0);
            BlockPos repeater = new BlockPos(1, 1, 1);
            BlockPos source = new BlockPos(1, 1, 2);
            helper.setBlock(lamp, Blocks.REDSTONE_LAMP);
            helper.setBlock(repeater, Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.SOUTH));
            helper.startSequence()
                    .thenExecute(() -> helper.setBlock(source, Blocks.REDSTONE_BLOCK))
                    .thenExecuteAfter(8, () -> helper.assertTrue(
                            helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                            "lamp behind a powered repeater did not light — the repeater's scheduled tick never ran "
                                    + describe(helper, repeater)))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "Entities tick in region mode: a pig spawned in mid-air falls under gravity."
    })
    static void entityTicks(final DynamicTest test) {
        test.onGameTest(helper -> {
            BlockPos spawnAt = new BlockPos(1, 3, 1);
            Pig pig = helper.spawn(EntityType.PIG, spawnAt);
            double startY = pig.getY();
            helper.startSequence()
                    .thenExecuteAfter(15, () -> helper.assertTrue(
                            pig.getY() < startY - 0.5,
                            "pig spawned at y=" + startY + " is still at y=" + pig.getY()
                                    + " after 15 ticks — entities are not ticking " + describe(helper, spawnAt)))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "Block entities tick in region mode: a hopper moves its item into the chest below it."
    })
    static void blockEntityTicks(final DynamicTest test) {
        test.onGameTest(helper -> {
            BlockPos chest = new BlockPos(1, 1, 1);
            BlockPos hopper = new BlockPos(1, 2, 1);
            helper.setBlock(chest, Blocks.CHEST);
            helper.setBlock(hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
            helper.startSequence()
                    .thenExecute(() -> helper.<HopperBlockEntity>getBlockEntity(hopper)
                            .setItem(0, new ItemStack(Items.DIAMOND)))
                    .thenExecuteAfter(12, () -> {
                        try {
                            helper.assertContainerContains(chest, Items.DIAMOND);
                        } catch (RuntimeException e) {
                            helper.fail("hopper did not move its item within 12 ticks — block entities are not ticking "
                                    + describe(helper, hopper));
                        }
                    })
                    .thenSucceed();
        });
    }
}
