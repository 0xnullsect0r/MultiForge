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
        return "[region=" + (region == null ? "none"
                : region.id() + " state=" + region.state()
                        + " ownedChunks=" + region.ownedChunkSnapshot().size()
                        + " holdersOwned=" + (manager == null ? "no-manager" : manager.holdersOwnedBy(region.id()).size())
                        + " avgMspt=" + (host.scheduler().mspt(region) == null ? "unregistered" : host.scheduler().mspt(region).averageMillis()))
                + " holderAtChunk=" + (manager != null && manager.holderAt(chunk) != null)
                + " regionsInWorld=" + regionizer.regions().size() + "]";
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "A scheduled block tick fires in region mode: a redstone lamp that loses power",
            "schedules its own tick to turn off, and is unlit once that tick has run."
    })
    static void scheduledBlockTickFires(final DynamicTest test) {
        test.onGameTest(helper -> {
            BlockPos lamp = new BlockPos(1, 1, 1);
            BlockPos source = new BlockPos(1, 2, 1);
            helper.startSequence()
                    .thenExecute(() -> helper.setBlock(lamp, Blocks.REDSTONE_LAMP))
                    .thenExecute(() -> helper.setBlock(source, Blocks.REDSTONE_BLOCK))
                    .thenExecuteAfter(2, () -> helper.assertBlockProperty(lamp, RedstoneLampBlock.LIT, true))
                    // Unpowered, the lamp schedules a 4-tick delayed tick to turn itself off.
                    .thenExecute(() -> helper.setBlock(source, Blocks.AIR))
                    .thenExecuteAfter(1, () -> helper.assertTrue(
                            helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                            "lamp turned off before its scheduled tick could have run " + describe(helper, lamp)))
                    .thenExecuteAfter(8, () -> helper.assertTrue(
                            !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                            "unpowered lamp is still lit — its scheduled tick never ran " + describe(helper, lamp)))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "A block tick scheduled for the current tick from a region's mailbox, before the",
            "region's scheduled ticks run, fires in that same tick (LevelTicks' late-due path:",
            "the region's due chunks were bucketed before its mailbox ran)."
    })
    static void tickScheduledFromTheMailboxForNowRunsThisTick(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off": no mailbox
                return;
            }
            BlockPos lamp = new BlockPos(1, 1, 1);
            BlockPos abs = helper.absolutePos(lamp);
            helper.startSequence()
                    // Lit and unpowered, with no neighbour update: nothing schedules its tick yet.
                    .thenExecute(() -> helper.getLevel().setBlock(
                            abs, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), 2))
                    .thenExecute(() -> host.taskQueue().queueChunkTask(
                            helper.getLevel().mfWorldRef(), abs.getX() >> 4, abs.getZ() >> 4,
                            () -> helper.getLevel().scheduleTick(abs, Blocks.REDSTONE_LAMP, 0)))
                    .thenExecuteAfter(1, () -> helper.assertTrue(
                            !helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT),
                            "a tick scheduled for now from the mailbox did not run in the same tick " + describe(helper, lamp)))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "sendBlockUpdated on a region worker re-paths the region's navigating mobs: one",
            "indexed before the region ticked and one that started navigating mid-tick."
    })
    static void blockChangeOnAWorkerRepathsOldAndNewMobs(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off": no mailbox
                return;
            }
            BlockPos start = new BlockPos(0, 1, 0);
            BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
            // Bees: flying navigation paths from mid-air, where a fresh ground mob could not.
            net.minecraft.world.entity.animal.Bee early = helper.spawn(EntityType.BEE, start);
            early.setNoAi(true);
            java.util.concurrent.atomic.AtomicReference<String> result = new java.util.concurrent.atomic.AtomicReference<>();
            BlockPos abs = helper.absolutePos(start);
            helper.startSequence()
                    .thenExecuteAfter(1, () -> host.taskQueue().queueChunkTask(
                            helper.getLevel().mfWorldRef(), abs.getX() >> 4, abs.getZ() >> 4, () -> {
                                net.minecraft.world.entity.animal.Bee late = EntityType.BEE.create(helper.getLevel());
                                late.moveTo(early.getX(), early.getY(), early.getZ());
                                late.setNoAi(true);
                                helper.getLevel().addFreshEntity(late);
                                early.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
                                late.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
                                net.minecraft.world.level.pathfinder.Path earlyPath = early.getNavigation().getPath();
                                net.minecraft.world.level.pathfinder.Path latePath = late.getNavigation().getPath();
                                if (earlyPath == null || latePath == null || earlyPath.getNodeCount() < 2) {
                                    result.set("no path to test with: " + earlyPath + " / " + latePath);
                                    return;
                                }
                                BlockPos node = earlyPath.getNodePos(1);
                                helper.getLevel().setBlock(node, Blocks.STONE.defaultBlockState(), 3);
                                boolean earlyRepathed = early.getNavigation().getPath() != earlyPath;
                                boolean lateRepathed = late.getNavigation().getPath() != latePath;
                                result.set(earlyRepathed && lateRepathed
                                        ? "ok"
                                        : "re-pathed: indexed mob " + earlyRepathed + ", mid-tick mob " + lateRepathed);
                            }))
                    .thenExecuteAfter(2, () -> helper.assertTrue(
                            "ok".equals(result.get()), String.valueOf(result.get()) + " " + describe(helper, start)))
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

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 200)
    @TestHolder(description = {
            "A region worker writing into a chunk another region owns is rerouted to that",
            "region instead of mutating it concurrently, and the write still lands."
    })
    static void crossRegionSetBlockIsRerouted(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (net.multiforge.runtime.ownership.OwnershipEnforcer.mode() == net.multiforge.runtime.ownership.OwnershipEnforcer.Mode.STRICT) {
                // Strict mode turns this deliberate violation into a crash by design.
                helper.succeed();
                return;
            }
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            helper.assertTrue(host != null, "runtime must be installed");
            net.minecraft.server.level.ServerLevel level = helper.getLevel();
            BlockPos here = helper.absolutePos(new BlockPos(1, 1, 1));
            // 2000 blocks away: far outside the merge radius, so a separate region.
            BlockPos far = here.offset(2000, 0, 0);
            int farChunkX = far.getX() >> 4;
            int farChunkZ = far.getZ() >> 4;
            level.setChunkForced(farChunkX, farChunkZ, true);
            long before = net.multiforge.runtime.diagnostics.ProbeRegistry.get("Level.setBlock:cross-region");
            java.util.concurrent.atomic.AtomicReference<String> onWorker = new java.util.concurrent.atomic.AtomicReference<>();
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(
                            level.hasChunk(farChunkX, farChunkZ)
                                    && host.regionizerFor(level.mfWorldRef()).regionAtChunk(farChunkX, farChunkZ) != null,
                            "far chunk not loaded and regionized yet"))
                    .thenExecute(() -> {
                        Region nearRegion = host.regionizerFor(level.mfWorldRef()).regionAtChunk(here.getX() >> 4, here.getZ() >> 4);
                        Region farRegion = host.regionizerFor(level.mfWorldRef()).regionAtChunk(farChunkX, farChunkZ);
                        helper.assertTrue(nearRegion != null && farRegion != null && !nearRegion.id().equals(farRegion.id()),
                                "expected two distinct regions " + describe(helper, new BlockPos(1, 1, 1)));
                        // Runs on the near region's worker during its next tick.
                        host.taskQueue().queueChunkTask(level.mfWorldRef(), here.getX() >> 4, here.getZ() >> 4, () -> {
                            onWorker.set(Thread.currentThread().getName());
                            level.setBlock(far, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                        });
                    })
                    .thenWaitUntil(() -> helper.assertTrue(onWorker.get() != null, "worker task has not run yet"))
                    .thenWaitUntil(() -> helper.assertTrue(
                            level.getBlockState(far).is(Blocks.GOLD_BLOCK), "rerouted write has not landed yet"))
                    .thenExecute(() -> {
                        helper.assertTrue(onWorker.get().startsWith("multiforge-tick-"),
                                "task ran on " + onWorker.get() + ", not a region worker");
                        helper.assertTrue(
                                net.multiforge.runtime.diagnostics.ProbeRegistry.get("Level.setBlock:cross-region") > before,
                                "the cross-region write was applied inline instead of being rerouted");
                        level.setBlock(far, Blocks.AIR.defaultBlockState(), 3);
                        level.setChunkForced(farChunkX, farChunkZ, false);
                    })
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = {
            "A rerouted setBlock / addFreshEntity returns what Vanilla would have:",
            "true when the block changes or the entity is added, false otherwise."
    })
    static void reroutedCallsReturnVanillaResults(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (net.multiforge.runtime.ownership.OwnershipEnforcer.mode() == net.multiforge.runtime.ownership.OwnershipEnforcer.Mode.STRICT) {
                helper.succeed();
                return;
            }
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            helper.assertTrue(host != null, "runtime must be installed");
            net.minecraft.server.level.ServerLevel level = helper.getLevel();
            BlockPos here = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos far = here.offset(-2000, 0, 0);
            BlockPos farSky = new BlockPos(far.getX(), level.getMaxBuildHeight() - 1, far.getZ());
            BlockPos farOutside = new BlockPos(far.getX(), level.getMaxBuildHeight() + 10, far.getZ());
            int farChunkX = far.getX() >> 4;
            int farChunkZ = far.getZ() >> 4;
            level.setChunkForced(farChunkX, farChunkZ, true);
            Pig pig = EntityType.PIG.create(level);
            helper.assertTrue(pig != null, "pig");
            pig.moveTo(far.getX() + 0.5, far.getY(), far.getZ() + 0.5, 0, 0);
            pig.setNoAi(true);
            pig.setNoGravity(true);
            java.util.concurrent.ConcurrentHashMap<String, Boolean> results = new java.util.concurrent.ConcurrentHashMap<>();
            Runnable onNearWorker = () -> host.taskQueue().queueChunkTask(level.mfWorldRef(), here.getX() >> 4, here.getZ() >> 4, () -> {
                results.put("gold", level.setBlock(far, Blocks.GOLD_BLOCK.defaultBlockState(), 3));
                results.put("sameState", level.setBlock(farSky, Blocks.AIR.defaultBlockState(), 3));
                results.put("outside", level.setBlock(farOutside, Blocks.STONE.defaultBlockState(), 3));
                results.put("pig", level.addFreshEntity(pig));
            });
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(
                            level.hasChunk(farChunkX, farChunkZ)
                                    && host.regionizerFor(level.mfWorldRef()).regionAtChunk(farChunkX, farChunkZ) != null,
                            "far chunk not loaded and regionized yet"))
                    .thenExecute(() -> level.setBlock(far, Blocks.AIR.defaultBlockState(), 3))
                    .thenExecute(onNearWorker)
                    .thenWaitUntil(() -> helper.assertTrue(results.containsKey("pig"), "worker task has not run yet"))
                    .thenWaitUntil(() -> helper.assertTrue(
                            level.getBlockState(far).is(Blocks.GOLD_BLOCK) && level.getEntity(pig.getUUID()) != null,
                            "rerouted block and entity have not landed yet"))
                    .thenExecute(() -> {
                        helper.assertTrue(results.get("gold"), "rerouted setBlock that changes a block returned false");
                        helper.assertFalse(results.get("sameState"), "rerouted setBlock of the existing state returned true");
                        helper.assertFalse(results.get("outside"), "rerouted setBlock outside the build height returned true");
                        helper.assertTrue(results.get("pig"), "rerouted addFreshEntity of a new entity returned false");
                        results.clear();
                    })
                    // Adding the same entity again: Vanilla refuses a UUID the level already holds.
                    .thenExecute(() -> host.taskQueue().queueChunkTask(level.mfWorldRef(), here.getX() >> 4, here.getZ() >> 4,
                            () -> results.put("pigAgain", level.addFreshEntity(pig))))
                    .thenWaitUntil(() -> helper.assertTrue(results.containsKey("pigAgain"), "worker task has not run yet"))
                    .thenExecute(() -> {
                        helper.assertFalse(results.get("pigAgain"), "rerouted addFreshEntity of a present UUID returned true");
                        pig.discard();
                        level.setBlock(far, Blocks.AIR.defaultBlockState(), 3);
                        level.setChunkForced(farChunkX, farChunkZ, false);
                    })
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 400)
    @TestHolder(description = {
            "Random ticks run in the region owning the chunk: unsupported leaves decay",
            "in a forced chunk, and the chunk work was run by region workers."
    })
    static void randomTicksRunInRegions(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            helper.assertTrue(host != null, "runtime must be installed");
            net.minecraft.server.level.ServerLevel level = helper.getLevel();
            BlockPos rel = new BlockPos(1, 2, 1);
            BlockPos abs = helper.absolutePos(rel);
            int cx = abs.getX() >> 4;
            int cz = abs.getZ() >> 4;
            net.minecraft.world.level.GameRules.IntegerValue speed = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
            int oldSpeed = speed.get();
            net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(cx, cz);
            long before = net.multiforge.runtime.diagnostics.ProbeRegistry.get("region-tick.chunk-ticks");
            // A force-ticking ticket (NeoForge: forceTicks = true) random-ticks the
            // chunk without players nearby; a high speed makes the decay near-certain.
            speed.set(1024, level.getServer());
            level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, chunk, 2, chunk, true);
            helper.setBlock(rel, Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false)
                    .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 7));
            Runnable restore = () -> {
                speed.set(oldSpeed, level.getServer());
                level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, chunk, 2, chunk, true);
            };
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(
                            helper.getBlockState(rel).isAir(), "leaves have not decayed yet " + describe(helper, rel)))
                    .thenExecute(() -> {
                        restore.run();
                        helper.assertTrue(
                                net.multiforge.runtime.diagnostics.ProbeRegistry.get("region-tick.chunk-ticks") > before,
                                "no region ran chunk ticks");
                    })
                    .thenSucceed();
        });
    }
}
