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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.neoforged.testframework.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * GameTests for chunk-ticket changes made on a region worker
 * (multiforge-patches/04-chunk-system/): {@code ServerChunkCache.addTicketWithRadius}
 * / {@code removeTicketWithRadius} and {@code ServerLevel.setChunkForced} are the
 * server thread's, so a region's call is deferred to it (applied after the
 * barrier) with a rate-limited warning, and never throws — in strict mode too.
 */
@ForEachTest(groups = "multiforge.tickets")
public class TicketRerouteTests {
    private static final int TOGGLES = 6;
    private static final int PERIOD = 7;

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 400)
    @TestHolder(description = {
            "A region toggling a forced chunk and a region ticket every 7 ticks has each change",
            "deferred to the server thread, applied after the barrier, with Vanilla's return value."
    })
    static void workerTicketChangesAreDeferred(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                // mode = off: no region workers, Vanilla's inline tickets.
                helper.succeed();
                return;
            }
            ServerLevel level = helper.getLevel();
            BlockPos here = helper.absolutePos(new BlockPos(1, 1, 1));
            ChunkPos target = ChunkPos.containing(here.offset(64, 0, 64));
            long forcedBefore = ProbeRegistry.get("ServerLevel.setChunkForced:deferred-to-server-thread");
            long ticketBefore = ProbeRegistry.get("ServerChunkCache.addTicketWithRadius:deferred-to-server-thread")
                    + ProbeRegistry.get("ServerChunkCache.removeTicketWithRadius:deferred-to-server-thread");
            List<String> problems = new CopyOnWriteArrayList<>();
            List<Boolean> expectForced = new ArrayList<>();
            GameTestSequence seq = helper.startSequence();
            for (int i = 0; i < TOGGLES; i++) {
                boolean add = i % 2 == 0;
                expectForced.add(add);
                seq.thenExecuteAfter(PERIOD, () -> host.taskQueue().queueChunkTask(level.mfWorldRef(), here.getX() >> 4, here.getZ() >> 4, () -> {
                    boolean wasForced = level.getForceLoadedChunks().contains(target.pack());
                    boolean changed = level.setChunkForced(target.x(), target.z(), add);
                    if (changed != (wasForced != add)) problems.add("setChunkForced(" + add + ") returned " + changed);
                    if (level.getForceLoadedChunks().contains(target.pack()) != wasForced) {
                        problems.add("setChunkForced(" + add + ") applied on the worker");
                    }
                    if (add) level.getChunkSource().addTicketWithRadius(TicketType.FORCED, target, 1);
                    else level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, target, 1);
                }));
                seq.thenWaitUntil(() -> helper.assertTrue(
                        level.getForceLoadedChunks().contains(target.pack()) == add,
                        "the deferred setChunkForced(" + add + ") has not landed"));
            }
            seq.thenExecute(() -> {
                helper.assertTrue(problems.isEmpty(), "worker ticket calls misbehaved: " + problems);
                helper.assertTrue(
                        ProbeRegistry.get("ServerLevel.setChunkForced:deferred-to-server-thread") - forcedBefore >= TOGGLES,
                        "setChunkForced was not deferred on every toggle");
                long tickets = ProbeRegistry.get("ServerChunkCache.addTicketWithRadius:deferred-to-server-thread")
                        + ProbeRegistry.get("ServerChunkCache.removeTicketWithRadius:deferred-to-server-thread");
                helper.assertTrue(tickets - ticketBefore >= TOGGLES, "region tickets were not deferred on every toggle");
                helper.assertFalse(level.getForceLoadedChunks().contains(target.pack()), "the target chunk is still forced");
            })
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "A region the server thread ticks inline (one region in the level, or a hot region) changes",
            "tickets inline, as Vanilla does; a deferral there would run at once and re-enter itself."
    })
    static void serverThreadRegionTicketChangesApplyInline(final DynamicTest test) {
        test.onGameTest(helper -> {
            ServerLevel level = helper.getLevel();
            ChunkPos target = ChunkPos.containing(helper.absolutePos(new BlockPos(1, 1, 1)).offset(96, 0, 96));
            helper.assertTrue(level.getServer().isSameThread(), "GameTest body not on the server thread");
            helper.assertFalse(level.getForceLoadedChunks().contains(target.pack()), "target already forced");
            boolean[] changed = new boolean[2];
            OwnerToken.runAs(OwnerToken.forRegion(Long.MAX_VALUE - 7), () -> {
                changed[0] = level.setChunkForced(target.x(), target.z(), true);
                level.getChunkSource().addTicketWithRadius(TicketType.FORCED, target, 1);
            });
            helper.assertTrue(changed[0], "setChunkForced(true) did not report a change");
            helper.assertTrue(level.getForceLoadedChunks().contains(target.pack()), "setChunkForced(true) was not applied inline");
            OwnerToken.runAs(OwnerToken.forRegion(Long.MAX_VALUE - 7), () -> {
                level.getChunkSource().removeTicketWithRadius(TicketType.FORCED, target, 1);
                changed[1] = level.setChunkForced(target.x(), target.z(), false);
            });
            helper.assertTrue(changed[1], "setChunkForced(false) did not report a change");
            helper.assertFalse(level.getForceLoadedChunks().contains(target.pack()), "setChunkForced(false) was not applied inline");
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "A test's forced chunk is released only if the test added the force: the test's own",
            "chunk, forced by the GameTest runner and shared with batch neighbours, stays forced."
    })
    static void releasingASharedForceKeepsTheChunkForced(final DynamicTest test) {
        test.onGameTest(helper -> {
            ServerLevel level = helper.getLevel();
            ChunkPos own = ChunkPos.containing(helper.absolutePos(new BlockPos(1, 1, 1)));
            helper.assertTrue(level.getForceLoadedChunks().contains(own.pack()), "the runner did not force the test's chunk");
            TestForcedChunks.force(level, own).run();
            helper.assertTrue(level.getForceLoadedChunks().contains(own.pack()), "releasing a shared force un-forced the test's chunk");
            ChunkPos fresh = ChunkPos.containing(helper.absolutePos(new BlockPos(1, 1, 1)).offset(160, 0, 160));
            helper.assertFalse(level.getForceLoadedChunks().contains(fresh.pack()), "fresh chunk already forced");
            Runnable release = TestForcedChunks.force(level, fresh);
            helper.assertTrue(level.getForceLoadedChunks().contains(fresh.pack()), "force did not force a fresh chunk");
            release.run();
            helper.assertFalse(level.getForceLoadedChunks().contains(fresh.pack()), "release kept a force it added");
            helper.succeed();
        });
    }
}
