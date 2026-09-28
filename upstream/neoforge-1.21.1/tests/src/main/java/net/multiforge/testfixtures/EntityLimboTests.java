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
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.multiforge.neoforge.world.EntityAudit;
import net.multiforge.runtime.diagnostics.EntityCensus;
import net.multiforge.runtime.region.RegionPhase;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * GameTests for entity limbo: the census and heal behind {@code /multiforge
 * entities} ({@link EntityAudit}), and the entity-visibility deferral that
 * stops the race creating limbo entities ({@link RegionPhase} and the guard at
 * the head of {@code PersistentEntitySectionManager.updateChunkStatus}).
 *
 * <p>The race: a chunk demotion pumped on the server thread records "stop
 * ticking" for the entities in the chunk and applies it after releasing the
 * storage lock; a region worker moving one of those entities into a ticking
 * chunk in between starts it ticking, and the stale stop then takes it off the
 * tick list for good. {@code PersistentEntitySectionManager.mfTestHook} runs
 * exactly in that gap, so the tests replay the interleaving deterministically
 * on the server thread. Each test creates and removes its limbo within one
 * step, so no other test's census sees it.
 */
@ForEachTest(groups = "multiforge.entities")
public class EntityLimboTests {
    private static boolean ticks(ServerLevel level, Entity entity) {
        List<Entity> listed = new ArrayList<>();
        level.mfCopyEntityTickList(listed);
        return listed.contains(entity);
    }

    private static Pig floatingPig(GameTestHelper helper, BlockPos rel) {
        Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, rel);
        pig.setNoGravity(true);
        return pig;
    }

    /** The chunk east of the test's own, force-loaded so it ticks entities. */
    private static ChunkPos eastChunk(GameTestHelper helper) {
        ChunkPos home = new ChunkPos(helper.absolutePos(new BlockPos(1, 2, 1)));
        return new ChunkPos(home.x + 1, home.z);
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "The entity census adds up: a healthy level has no gaps, and the test's pigs",
            "are counted by type and in their region."
    })
    static void censusAddsUp(final DynamicTest test) {
        test.onGameTest(helper -> {
            ServerLevel level = helper.getLevel();
            for (int i = 0; i < 3; i++) floatingPig(helper, new BlockPos(1, 2, 1));
            helper.startSequence()
                    .thenExecuteAfter(2, () -> {
                        EntityCensus c = EntityAudit.take(level, false);
                        helper.assertTrue(c.healthy(), "a healthy level shows census gaps: " + c.summaryLine() + " " + c.samples());
                        helper.assertTrue(c.known() == c.visible() + c.hidden(), "known != visible + hidden: " + c.summaryLine());
                        helper.assertTrue(c.accessible() == c.visible(), "accessible != visible: " + c.summaryLine());
                        helper.assertTrue(c.ticking() == c.tickListed(), "ticking sections != tick list: " + c.summaryLine());
                        int pigs = c.byType().getOrDefault("minecraft:pig", 0);
                        helper.assertTrue(pigs >= 3, "census counts " + pigs + " pigs, expected at least 3");
                        int total = 0;
                        for (Map.Entry<Long, Integer> e : c.perRegion().entrySet()) total += e.getValue();
                        helper.assertTrue(total == c.hidden() + c.accessible(), "per-region counts do not add up: " + c.perRegion());
                    })
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "A limbo entity (visible and ticking state lost) is detected by the census,",
            "and the heal makes it visible and ticking again."
    })
    static void injectedLimboIsDetectedAndHealed(final DynamicTest test) {
        test.onGameTest(helper -> {
            ServerLevel level = helper.getLevel();
            Pig pig = floatingPig(helper, new BlockPos(1, 2, 1));
            PersistentEntitySectionManager<Entity> manager = level.mfEntityManager();
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(ticks(level, pig), "the pig never started ticking"))
                    .thenExecute(() -> {
                        EntityCensus before = EntityAudit.take(level, false);
                        // Lose the pig's ticking and tracking, as the race did.
                        manager.mfStopTicking(pig);
                        manager.mfStopTracking(pig);
                        EntityCensus limbo = EntityAudit.take(level, true);
                        EntityCensus after = EntityAudit.take(level, false);
                        helper.assertTrue(
                                limbo.tickingNotListed() == before.tickingNotListed() + 1
                                        && limbo.accessibleNotVisible() == before.accessibleNotVisible() + 1,
                                "the census missed the limbo pig: " + limbo.summaryLine());
                        helper.assertTrue(limbo.samples().stream().anyMatch(s -> s.startsWith("minecraft:pig")),
                                "no limbo sample names the pig: " + limbo.samples());
                        helper.assertTrue(limbo.healed() >= 2, "heal re-applied " + limbo.healed() + " transitions, expected 2");
                        helper.assertTrue(after.healthy(), "still in limbo after the heal: " + after.summaryLine());
                        helper.assertTrue(ticks(level, pig), "the healed pig is not ticking");
                        helper.assertTrue(level.getEntity(pig.getUUID()) == pig, "the healed pig is not visible");
                    })
                    .thenExecute(pig::discard)
                    .thenSucceed();
        });
    }

    /**
     * Moves {@code pig} into chunk {@code to} while a demotion of its own chunk
     * {@code from} runs, at the point the race hit: after the demotion released
     * the storage lock, before it applied the stop-ticking it recorded.
     */
    private static void demoteWhileMoving(PersistentEntitySectionManager<Entity> manager, Pig pig, ChunkPos from, ChunkPos to) {
        PersistentEntitySectionManager.mfTestHook = () -> {
            PersistentEntitySectionManager.mfTestHook = null;
            pig.setPos(to.getMiddleBlockX() + 0.5, pig.getY(), to.getMiddleBlockZ() + 0.5);
        };
        try {
            manager.updateChunkStatus(from, FullChunkStatus.BLOCK_TICKING);
            // The "worker's" move, when the demotion was held back and the hook did not run.
            if (PersistentEntitySectionManager.mfTestHook != null) {
                pig.setPos(to.getMiddleBlockX() + 0.5, pig.getY(), to.getMiddleBlockZ() + 0.5);
            }
        } finally {
            PersistentEntitySectionManager.mfTestHook = null;
        }
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 200)
    @TestHolder(description = {
            "Race reproduction: a pumped chunk demotion interleaved with a worker's entity move",
            "leaves the entity in limbo with the visibility deferral off (v1.10), and not with it on."
    })
    static void deferredVisibilityPreventsLimbo(final DynamicTest test) {
        test.onGameTest(helper -> {
            ServerLevel level = helper.getLevel();
            PersistentEntitySectionManager<Entity> manager = level.mfEntityManager();
            ChunkPos home = new ChunkPos(helper.absolutePos(new BlockPos(1, 2, 1)));
            ChunkPos east = eastChunk(helper);
            level.setChunkForced(east.x, east.z, true);
            Pig unguarded = floatingPig(helper, new BlockPos(1, 2, 1));
            Pig guarded = floatingPig(helper, new BlockPos(1, 2, 1));
            boolean killSwitch = RegionPhase.deferVisibility();
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(
                            manager.canPositionTick(east) && manager.canPositionTick(home) && ticks(level, unguarded) && ticks(level, guarded),
                            "chunks " + home + " / " + east + " not entity-ticking yet"))
                    .thenExecute(() -> {
                        // Kill switch off: the demotion runs while "workers" are in flight, as in v1.10.
                        RegionPhase.setDeferVisibility(false);
                        RegionPhase.beginWorkers();
                        try {
                            demoteWhileMoving(manager, unguarded, home, east);
                        } finally {
                            try {
                                RegionPhase.endWorkers();
                            } catch (IllegalStateException strict) {
                                // Strict mode fails the barrier on the unguarded transition, by design.
                            }
                            RegionPhase.setDeferVisibility(killSwitch);
                            manager.updateChunkStatus(home, FullChunkStatus.ENTITY_TICKING);
                        }
                        boolean limbo = !ticks(level, unguarded) && manager.canPositionTick(unguarded.blockPosition());
                        EntityCensus healed = EntityAudit.take(level, true);
                        helper.assertTrue(limbo, "without the deferral the interleaving should leave the pig in a ticking chunk"
                                + " but off the tick list (the v1.10 race); census " + healed.summaryLine());
                        helper.assertTrue(healed.tickingNotListed() >= 1 && ticks(level, unguarded),
                                "the heal did not put the limbo pig back on the tick list: " + healed.summaryLine());
                    })
                    .thenExecute(() -> {
                        // Kill switch on: the demotion is held back until the "barrier".
                        RegionPhase.setDeferVisibility(true);
                        RegionPhase.beginWorkers();
                        try {
                            demoteWhileMoving(manager, guarded, home, east);
                            helper.assertTrue(RegionPhase.pending() >= 1, "the demotion was not held back");
                            helper.assertTrue(manager.canPositionTick(home), "the held-back demotion was applied early");
                        } finally {
                            RegionPhase.endWorkers();
                            RegionPhase.setDeferVisibility(killSwitch);
                        }
                        helper.assertFalse(manager.canPositionTick(home), "the held-back demotion was never replayed");
                        manager.updateChunkStatus(home, FullChunkStatus.ENTITY_TICKING);
                        helper.assertTrue(ticks(level, guarded), "with the deferral the moved pig still fell off the tick list");
                        EntityCensus c = EntityAudit.take(level, false);
                        helper.assertTrue(c.healthy(), "census gaps after the guarded interleaving: " + c.summaryLine() + " " + c.samples());
                    })
                    .thenExecute(() -> {
                        unguarded.discard();
                        guarded.discard();
                        level.setChunkForced(east.x, east.z, false);
                    })
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "Held-back visibility changes replay in order, and each step leaves the entity",
            "in the same state as applying the changes directly (Vanilla)."
    })
    static void heldBackChangesReplayInOrderLikeVanilla(final DynamicTest test) {
        test.onGameTest(helper -> {
            ServerLevel level = helper.getLevel();
            PersistentEntitySectionManager<Entity> manager = level.mfEntityManager();
            ChunkPos home = new ChunkPos(helper.absolutePos(new BlockPos(1, 2, 1)));
            Pig pig = floatingPig(helper, new BlockPos(1, 2, 1));
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(ticks(level, pig), "the pig never started ticking"))
                    .thenExecute(() -> {
                        FullChunkStatus[] steps = { FullChunkStatus.BLOCK_TICKING, FullChunkStatus.FULL, FullChunkStatus.ENTITY_TICKING };
                        // Vanilla: apply each change directly and record the pig's state after it.
                        List<String> direct = new ArrayList<>();
                        for (FullChunkStatus s : steps) {
                            manager.updateChunkStatus(home, s);
                            direct.add(state(level, pig));
                        }
                        // Deferred: the same changes while "workers" run, with a probe after each.
                        List<String> replayed = new ArrayList<>();
                        boolean killSwitch = RegionPhase.deferVisibility();
                        RegionPhase.setDeferVisibility(true);
                        RegionPhase.beginWorkers();
                        try {
                            for (FullChunkStatus s : steps) {
                                manager.updateChunkStatus(home, s);
                                RegionPhase.defer(() -> replayed.add(state(level, pig)));
                            }
                            helper.assertTrue(replayed.isEmpty(), "a held-back change ran before the barrier");
                            helper.assertTrue(state(level, pig).equals(direct.get(direct.size() - 1)),
                                    "the pig's state changed before the barrier");
                        } finally {
                            RegionPhase.endWorkers();
                            RegionPhase.setDeferVisibility(killSwitch);
                        }
                        helper.assertTrue(replayed.equals(direct), "replayed " + replayed + " but Vanilla gives " + direct);
                        helper.assertTrue(direct.get(0).equals("visible,idle") && direct.get(2).equals("visible,ticking"),
                                "unexpected Vanilla transitions " + direct);
                    })
                    .thenExecute(pig::discard)
                    .thenSucceed();
        });
    }

    private static String state(ServerLevel level, Pig pig) {
        return (level.getEntity(pig.getUUID()) == pig ? "visible" : "hidden") + "," + (ticks(level, pig) ? "ticking" : "idle");
    }
}
