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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.region.RegionWorkerThread;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.registration.RegistrationHelper;

/** One test per unsafe finding of {@code docs/design/shared-state-audit.md} that a test can pin down. */
@ForEachTest(groups = "multiforge.sharedstate")
public class SharedStateAuditTests {
    private static final int DRAWS_PER_THREAD = 20_000;
    private static final int THREADS = 4;

    private static void onWorker(MultiThreadedSchedulerHost host, ServerLevel level, BlockPos pos, Runnable task) {
        host.taskQueue().queueChunkTask(level.mfWorldRef(), pos.getX() >> 4, pos.getZ() >> 4, task);
    }

    private static void inParallel(Runnable body) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                body.run();
            }, "shared-state-test-" + i);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "Concurrent scheduled ticks get unique sub-tick orders.")
    static void subTickOrdersAreUniqueAcrossThreads(final DynamicTest test) {
        test.onGameTest(helper -> {
            ServerLevel level = helper.getLevel();
            long[][] seen = new long[THREADS][DRAWS_PER_THREAD];
            java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
            try {
                inParallel(() -> {
                    long[] mine = seen[next.getAndIncrement()];
                    for (int i = 0; i < DRAWS_PER_THREAD; i++) mine[i] = level.nextSubTickCount();
                });
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            long distinct = Arrays.stream(seen).flatMapToLong(LongStream::of).distinct().count();
            helper.assertTrue(distinct == (long) THREADS * DRAWS_PER_THREAD,
                    "only " + distinct + " unique orders from " + THREADS * DRAWS_PER_THREAD + " draws");
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = "A scheduled tick on a region worker counts as handling a tick, as pistons expect.")
    static void regionBlockTickCountsAsHandlingATick(final DynamicTest test, final RegistrationHelper reg) {
        AtomicReference<String> seen = new AtomicReference<>();
        final var probe = reg.blocks().register("handling_tick_probe", key -> new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key))) {
            @Override
            protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
                boolean worker = Thread.currentThread() instanceof RegionWorkerThread;
                seen.set(worker ? String.valueOf(level.isHandlingTick()) : "not on a worker");
            }
        }).withLang("Handling tick probe");
        test.onGameTest(helper -> {
            if (MultiForgeRegionizedRuntime.current() == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            seen.set(null);
            BlockPos pos = new BlockPos(1, 1, 1);
            helper.startSequence()
                    .thenExecute(() -> helper.setBlock(pos, probe.get()))
                    .thenExecute(() -> helper.getLevel().scheduleTick(helper.absolutePos(pos), probe.get(), 1))
                    .thenWaitUntil(() -> helper.assertTrue(seen.get() != null, "the scheduled tick has not run"))
                    .thenExecute(() -> helper.assertTrue("true".equals(seen.get()), "isHandlingTick during a region block tick: " + seen.get()))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = "Reading another region's removed block entity changes nothing in that chunk.")
    static void foreignBlockEntityReadDoesNotMutate(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            ServerLevel level = helper.getLevel();
            BlockPos here = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos far = here.offset(2000, 0, 0);
            ChunkPos farChunk = ChunkPos.containing(far);
            Runnable release = TestForcedChunks.force(level, farChunk);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            level.getServer().managedBlock(() -> level.isPositionEntityTicking(far) || System.nanoTime() > deadline);
            helper.assertTrue(level.isPositionEntityTicking(far), "the far chunk did not load within 60 s");
            AtomicReference<BlockEntity> placed = new AtomicReference<>();
            AtomicReference<String> outcome = new AtomicReference<>();
            helper.addEndListener(passed -> {
                if (placed.get() != null) placed.get().clearRemoved();
                level.setBlock(far, Blocks.AIR.defaultBlockState(), 3);
                release.run();
            });
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(
                            host.regionizerFor(level.mfWorldRef()).regionAtChunk(farChunk.x(), farChunk.z()) != null,
                            "far chunk has no region yet"))
                    .thenExecute(() -> {
                        level.setBlock(far, Blocks.CHEST.defaultBlockState(), 3);
                        placed.set(level.getBlockEntity(far));
                        helper.assertTrue(placed.get() != null, "no chest at the far position");
                        placed.get().setRemoved();
                        LevelChunk chunk = level.getChunkAt(far);
                        onWorker(host, level, here, () -> {
                            BlockEntity read = chunk.getBlockEntity(far);
                            boolean kept = chunk.getBlockEntities().get(far) == placed.get();
                            outcome.set(read == null && kept ? "ok" : "read=" + read + " keptInMap=" + kept);
                        });
                    })
                    .thenWaitUntil(() -> helper.assertTrue(outcome.get() != null, "worker task has not run"))
                    .thenExecute(() -> helper.assertTrue("ok".equals(outcome.get()), outcome.get()))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = "A POI search runs its caller's filter outside the POI manager's monitor.")
    static void poiFilterRunsOutsideTheMonitor(final DynamicTest test) {
        test.onGameTest(helper -> {
            ServerLevel level = helper.getLevel();
            PoiManager poi = level.getPoiManager();
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            poi.add(pos, BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.MEETING));
            helper.addEndListener(passed -> poi.remove(pos));
            AtomicBoolean ran = new AtomicBoolean();
            AtomicBoolean held = new AtomicBoolean();
            poi.findClosest(type -> type.is(PoiTypes.MEETING), candidate -> {
                ran.set(true);
                held.set(Thread.holdsLock(poi));
                return true;
            }, pos, 2, PoiManager.Occupancy.ANY);
            helper.assertTrue(ran.get(), "the filter never ran");
            helper.assertFalse(held.get(), "the filter ran while holding the POI monitor");
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = "A placement's block capture on a worker stays on that worker.")
    static void blockCaptureStaysOnItsWorker(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            ServerLevel level = helper.getLevel();
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            AtomicReference<String> outcome = new AtomicReference<>();
            helper.startSequence()
                    .thenExecute(() -> onWorker(host, level, pos, () -> {
                        level.mfSetCapturingBlocks(true);
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                        int mine = level.mfCapturedBlocks().size();
                        boolean sharedFlag = level.captureBlockSnapshots;
                        int shared = level.capturedBlockSnapshots.size();
                        level.mfCapturedBlocks().clear();
                        level.mfSetCapturingBlocks(false);
                        outcome.set(mine == 1 && !sharedFlag && shared == 0
                                ? "ok"
                                : "captured=" + mine + " sharedFlag=" + sharedFlag + " sharedList=" + shared);
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(outcome.get() != null, "worker task has not run"))
                    .thenExecute(() -> helper.assertTrue("ok".equals(outcome.get()), outcome.get()))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "Concurrent draws from one random sequence give exactly the values of serial draws.")
    static void randomSequenceDrawsAreNotLostOrRepeated(final DynamicTest test) {
        test.onGameTest(helper -> {
            Identifier id = Identifier.fromNamespaceAndPath("multiforge", "audit_sequence");
            long[] serial = new long[THREADS * DRAWS_PER_THREAD];
            RandomSource serialSource = new RandomSequences().get(id, 42L);
            for (int i = 0; i < serial.length; i++) serial[i] = serialSource.nextLong();
            Arrays.sort(serial);
            RandomSequences shared = new RandomSequences();
            long[] drawn = new long[serial.length];
            java.util.concurrent.atomic.AtomicInteger slot = new java.util.concurrent.atomic.AtomicInteger();
            try {
                inParallel(() -> {
                    RandomSource source = shared.get(id, 42L);
                    for (int i = 0; i < DRAWS_PER_THREAD; i++) drawn[slot.getAndIncrement()] = source.nextLong();
                });
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            Arrays.sort(drawn);
            helper.assertTrue(Arrays.equals(serial, drawn), "concurrent draws differ from the sequence's serial values");
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = "A player's chunk move from a region worker waits for the barrier.")
    static void workerPlayerChunkMoveWaitsForTheBarrier(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            ServerLevel level = helper.getLevel();
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            var player = helper.makeTickingMockServerPlayerInLevel(GameType.SPECTATOR);
            AtomicReference<String> outcome = new AtomicReference<>();
            helper.startSequence()
                    .thenExecute(() -> onWorker(host, level, pos, () -> {
                        long before = ProbeRegistry.get("player.chunk-move.deferred");
                        level.getChunkSource().move(player);
                        outcome.set(ProbeRegistry.get("player.chunk-move.deferred") > before ? "ok" : "the move ran on the worker");
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(outcome.get() != null, "worker task has not run"))
                    .thenExecute(() -> helper.assertTrue("ok".equals(outcome.get()), outcome.get()))
                    .thenSucceed();
        });
    }
}
