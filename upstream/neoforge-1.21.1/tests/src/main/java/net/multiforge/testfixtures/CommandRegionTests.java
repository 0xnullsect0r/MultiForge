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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * Commands started from region code. A region the server thread ticks itself
 * (the only region of its level, a hot region, the global region) runs under a
 * region token while nothing else ticks; a command there must run inline. It
 * used to be "deferred to the server thread" — the very thread it was on —
 * which ran it at once, re-entered the deferral and overflowed the stack: any
 * command block in a small world crashed the server.
 */
@ForEachTest(groups = "multiforge.commands")
public class CommandRegionTests {

    private static long regionIdAt(GameTestHelper helper, BlockPos rel) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return -1L;
        BlockPos abs = helper.absolutePos(rel);
        ThreadedRegionizer regionizer = host.regionizerForOrNull(helper.getLevel().mfWorldRef());
        Region region = regionizer == null ? null : regionizer.regionAtChunk(abs.getX() >> 4, abs.getZ() >> 4);
        return region == null ? -1L : region.id().value();
    }

    private static String setblock(GameTestHelper helper, BlockPos rel, String block) {
        BlockPos abs = helper.absolutePos(rel);
        return "setblock " + abs.getX() + " " + abs.getY() + " " + abs.getZ() + " " + block;
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "A command run under a region token on the server thread, with no region worker",
            "running (how a region the server thread ticks itself runs a command block), runs",
            "inline instead of deferring to itself until the stack overflows."
    })
    static void commandUnderARegionTokenOnTheServerThreadRunsInline(final DynamicTest test) {
        test.onGameTest(helper -> {
            long region = regionIdAt(helper, BlockPos.ZERO);
            if (region < 0) {
                helper.succeed(); // mode = "off": no regions, no token
                return;
            }
            BlockPos target = new BlockPos(1, 1, 1);
            ServerLevel level = helper.getLevel();
            OwnerToken.runAs(OwnerToken.forRegion(region), () -> level.getServer()
                    .getCommands()
                    .performPrefixedCommand(
                            level.getServer().createCommandSourceStack().withSuppressedOutput(),
                            setblock(helper, target, "minecraft:gold_block")));
            // Inline: the block is there right away, not after the tick.
            helper.assertBlockPresent(Blocks.GOLD_BLOCK, target);
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "An impulse command block powered in a region runs its command, and a conditional",
            "chain command block behind it sees that the command succeeded and runs too.",
            "On a region worker the whole command-block tick is deferred to the server thread,",
            "so the success count the chain reads is the command's own."
    })
    static void commandBlockChainSeesItsCommandsSuccess(final DynamicTest test) {
        test.onGameTest(helper -> {
            BlockPos impulse = new BlockPos(0, 1, 1);
            BlockPos chain = new BlockPos(1, 1, 1);
            BlockPos power = new BlockPos(0, 2, 1);
            BlockPos first = new BlockPos(0, 1, 0);
            BlockPos second = new BlockPos(2, 1, 0);

            BlockState impulseState = Blocks.COMMAND_BLOCK.defaultBlockState().setValue(CommandBlock.FACING, Direction.EAST);
            BlockState chainState = Blocks.CHAIN_COMMAND_BLOCK
                    .defaultBlockState()
                    .setValue(CommandBlock.FACING, Direction.EAST)
                    .setValue(CommandBlock.CONDITIONAL, true);
            helper.startSequence()
                    .thenExecute(() -> {
                        helper.setBlock(impulse, impulseState);
                        helper.setBlock(chain, chainState);
                        CommandBlockEntity a = helper.getBlockEntity(impulse);
                        a.getCommandBlock().setCommand(setblock(helper, first, "minecraft:gold_block"));
                        CommandBlockEntity b = helper.getBlockEntity(chain);
                        b.getCommandBlock().setCommand(setblock(helper, second, "minecraft:diamond_block"));
                        b.setAutomatic(true);
                    })
                    .thenExecuteAfter(1, () -> helper.setBlock(power, Blocks.REDSTONE_BLOCK))
                    .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.GOLD_BLOCK, first))
                    .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.DIAMOND_BLOCK, second))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "A repeating command block keeps running without crashing the server (it re-runs",
            "every tick; before the fix the first run in a single-region level overflowed the stack)."
    })
    static void repeatingCommandBlockKeepsRunning(final DynamicTest test) {
        test.onGameTest(helper -> {
            BlockPos block = new BlockPos(1, 1, 1);
            BlockPos power = new BlockPos(1, 2, 1);
            BlockPos target = new BlockPos(0, 1, 0);
            java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
            helper.startSequence()
                    .thenExecute(() -> {
                        helper.setBlock(block, Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState());
                        CommandBlockEntity be = helper.getBlockEntity(block);
                        // Toggles the target each run, so several runs are observable.
                        be.getCommandBlock().setCommand("execute if block " + abs(helper, target)
                                + " minecraft:air run " + setblock(helper, target, "minecraft:stone"));
                    })
                    .thenExecuteAfter(1, () -> helper.setBlock(power, Blocks.REDSTONE_BLOCK))
                    .thenWaitUntil(() -> helper.assertBlockPresent(Blocks.STONE, target))
                    .thenExecute(() -> helper.setBlock(target, Blocks.AIR))
                    .thenWaitUntil(() -> {
                        helper.assertBlockPresent(Blocks.STONE, target);
                        seen.incrementAndGet();
                    })
                    .thenExecute(() -> helper.assertTrue(seen.get() > 0, "repeating command block ran only once"))
                    .thenSucceed();
        });
    }

    private static String abs(GameTestHelper helper, BlockPos rel) {
        BlockPos a = helper.absolutePos(rel);
        return a.getX() + " " + a.getY() + " " + a.getZ();
    }
}
