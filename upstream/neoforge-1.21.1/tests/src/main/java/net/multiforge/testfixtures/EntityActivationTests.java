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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.level.block.Blocks;
import net.multiforge.neoforge.world.EntityActivation;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * GameTests for entity activation range and the push cap ({@link
 * EntityActivation}).
 *
 * <p>The GameTest server runs with the defaults (activation on, push cap 8),
 * and NeoForge's suite passes with them. GameTest structures have no players,
 * so every mob in them older than 20 ticks is distant. These tests register
 * their own mobs ({@link EntityActivation#testSubject}), which counts their
 * full and inactive ticks and evaluates them even with {@code
 * -Dmultiforge.entities.activation=false}, and give a subject a pretend player
 * position where a test needs one ({@link EntityActivation#testViewer});
 * neither affects any other entity. In mode {@code off} nothing is throttled,
 * which the tests assert instead.
 */
@ForEachTest(groups = "multiforge.entities")
public class EntityActivationTests {
    private static boolean regionized() {
        return MultiForgeRegionizedRuntime.current() != null;
    }

    /**
     * Mode {@code off}: activation and the push cap are forced off and no region
     * pass runs, so nothing is throttled or counted. Asserts that and succeeds.
     */
    private static boolean offModeChecked(GameTestHelper helper) {
        if (regionized()) return false;
        helper.assertFalse(EntityActivation.enabled(), "mode off left activation on");
        helper.assertTrue(EntityActivation.maxEntityCollisions() == 0, "mode off left the push cap on");
        helper.succeed();
        return true;
    }

    /** A 1×2 water column walled with glass, on the template's floor (relative y 1). */
    private static BlockPos pool(GameTestHelper helper) {
        for (int x = 0; x <= 2; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = 2; y <= 3; y++) {
                    helper.setBlock(x, y, z, x == 1 && z == 1 ? Blocks.WATER : Blocks.GLASS);
                }
            }
        }
        return new BlockPos(1, 2, 1);
    }

    /** A real player close enough to wake {@code mob} (a mock player of another test). */
    private static boolean playerNear(GameTestHelper helper, Mob mob, int range) {
        for (ServerPlayer p : helper.getLevel().players()) {
            if (!p.isSpectator() && Math.abs(p.getX() - mob.getX()) <= range && Math.abs(p.getZ() - mob.getZ()) <= range) {
                return true;
            }
        }
        return false;
    }

    /** Throws until {@code mob} has run {@code ticks} ticks since its counts were reset. */
    private static int[] countsAfter(GameTestHelper helper, Mob mob, int ticks) {
        int[] c = EntityActivation.testCounts(mob);
        helper.assertTrue(c[0] + c[1] >= ticks, "only " + (c[0] + c[1]) + " of " + ticks + " ticks counted so far for "
                + mob.getType().toShortString() + " at " + mob.blockPosition() + " (tickCount " + mob.tickCount
                + (mob.isRemoved() ? ", removed" : "") + ")");
        return c;
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3_FLOOR, timeoutTicks = 600)
    @TestHolder(description = {
            "A squid far from every player runs a full tick about one tick in twenty (at",
            "least once in any 80), and its despawn check still runs every tick."
    })
    static void distantSquidTicksOneInTwentyAndStillDespawns(final DynamicTest test) {
        boolean[] armed = { false };
        Mob[] target = { null };
        test.eventListeners().forge().addListener((MobDespawnEvent event) -> {
            if (armed[0] && event.getEntity() == target[0]) event.setResult(MobDespawnEvent.Result.ALLOW);
        });
        test.onGameTest(helper -> {
            if (offModeChecked(helper)) return;
            Squid squid = helper.spawn(EntityType.SQUID, pool(helper));
            target[0] = squid;
            squid.setPersistenceRequired();
            EntityActivation.testSubject(squid);
            int window = 80;
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(squid.tickCount >= 25, "the squid is still young"))
                    .thenExecute(() -> EntityActivation.testResetCounts(squid))
                    .thenWaitUntil(() -> {
                        // Another test's mock player nearby wakes the squid: start the window over.
                        if (playerNear(helper, squid, 24)) EntityActivation.testResetCounts(squid);
                        countsAfter(helper, squid, window);
                    })
                    .thenExecute(() -> {
                        int[] c = EntityActivation.testCounts(squid);
                        if (regionized()) {
                            helper.assertTrue(c[0] >= 1, "the distant squid never woke in " + window + " ticks: " + c[0] + "/" + c[1]);
                            helper.assertTrue(c[0] <= 8, "the distant squid ran " + c[0] + " full ticks of " + (c[0] + c[1]));
                            helper.assertTrue(c[1] >= window - 8, "the distant squid skipped only " + c[1] + " ticks");
                        } else {
                            helper.assertTrue(c[1] == 0, "mode off throttled the squid: " + c[1] + " inactive ticks");
                        }
                        armed[0] = true;
                    })
                    // checkDespawn runs every tick, before the activation check: the
                    // despawn lands on the next tick, not on the next wake tick.
                    .thenExecuteAfter(3, () -> helper.assertTrue(squid.isRemoved(), "the inactive squid did not despawn"))
                    .thenExecute(() -> EntityActivation.testRelease(List.of(squid)))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3_FLOOR, timeoutTicks = 400)
    @TestHolder(description = {
            "A squid next to a player runs a full tick every tick."
    })
    static void nearMobTicksEveryTick(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (offModeChecked(helper)) return;
            Squid squid = helper.spawn(EntityType.SQUID, pool(helper));
            squid.setPersistenceRequired();
            EntityActivation.testSubject(squid);
            EntityActivation.testViewer(squid, squid.position().add(4, 0, 4));
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(squid.tickCount >= 25, "the squid is still young"))
                    .thenExecute(() -> EntityActivation.testResetCounts(squid))
                    .thenWaitUntil(() -> countsAfter(helper, squid, 60))
                    .thenExecute(() -> {
                        int[] c = EntityActivation.testCounts(squid);
                        helper.assertTrue(c[1] == 0, "the near squid skipped " + c[1] + " of " + (c[0] + c[1]) + " ticks");
                    })
                    .thenExecute(() -> {
                        EntityActivation.testRelease(List.of(squid));
                        squid.discard();
                    })
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3_FLOOR, timeoutTicks = 500)
    @TestHolder(description = {
            "Immune mobs far from every player tick every tick: an animal in love and",
            "one hurt in the last 100 ticks; bosses and the warden are exempt by tag."
    })
    static void exemptMobsTickEveryTick(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (offModeChecked(helper)) return;
            helper.assertTrue(EntityActivation.isExemptType(EntityType.WARDEN), "the warden is not in #multiforge:activation_exempt");
            helper.assertTrue(EntityActivation.isExemptType(EntityType.WITHER), "the wither (#c:bosses) is not exempt");
            helper.assertTrue(EntityActivation.isExemptType(EntityType.ENDER_DRAGON), "the ender dragon (#c:bosses) is not exempt");
            helper.assertFalse(EntityActivation.isExemptType(EntityType.PIG), "pigs are exempt");
            Pig lover = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(0, 2, 1));
            Cow hurt = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(2, 2, 1));
            Pig control = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 2, 1));
            // Without AI the hurt cow is not knocked off the platform into a chunk that does not tick entities.
            hurt.setNoAi(true);
            lover.setNoAi(true);
            List<Mob> mobs = List.of(lover, hurt, control);
            boolean[] playerSeen = { false };
            for (Mob m : mobs) {
                m.setPersistenceRequired();
                EntityActivation.testSubject(m);
            }
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(control.tickCount >= 25, "the mobs are still young"))
                    .thenExecute(() -> {
                        long now = helper.getLevel().getGameTime();
                        helper.assertTrue(now - control.mfLastDamageStamp() >= 100, "the control pig was hurt recently");
                        lover.setInLove(null);
                        hurt.hurt(helper.getLevel().damageSources().generic(), 0.5F);
                        for (Mob m : mobs) EntityActivation.testResetCounts(m);
                    })
                    .thenWaitUntil(() -> {
                        // A near player only makes a mob more active; it just voids the control.
                        if (playerNear(helper, control, 40)) playerSeen[0] = true;
                        countsAfter(helper, control, 60);
                        countsAfter(helper, lover, 60);
                        countsAfter(helper, hurt, 60);
                    })
                    .thenExecute(() -> {
                        int[] love = EntityActivation.testCounts(lover);
                        int[] ouch = EntityActivation.testCounts(hurt);
                        int[] none = EntityActivation.testCounts(control);
                        helper.assertTrue(love[1] == 0, "the pig in love skipped " + love[1] + " ticks");
                        helper.assertTrue(ouch[1] == 0, "the hurt cow skipped " + ouch[1] + " ticks");
                        if (regionized() && !playerSeen[0]) {
                            helper.assertTrue(none[1] > 0, "the control pig was never throttled: " + none[0] + "/" + none[1]);
                        } else {
                            helper.assertTrue(none[1] == 0, "mode off throttled the control pig");
                        }
                    })
                    .thenExecute(() -> {
                        EntityActivation.testRelease(mobs);
                        for (Mob m : mobs) m.discard();
                    })
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3_FLOOR, timeoutTicks = 200)
    @TestHolder(description = {
            "With the push cap on, 30 pigs in one block still take cramming damage",
            "(maxEntityCramming 24): the cramming check counts every overlapping mob."
    })
    static void crammingDamageSurvivesThePushCap(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (regionized()) {
                helper.assertTrue(EntityActivation.maxEntityCollisions() == 8,
                        "push cap is " + EntityActivation.maxEntityCollisions() + ", expected the default 8");
            } else {
                helper.assertTrue(EntityActivation.maxEntityCollisions() == 0, "mode off caps pushes");
            }
            BlockPos cell = pool(helper);
            helper.setBlock(cell, Blocks.AIR);
            helper.setBlock(cell.above(), Blocks.AIR);
            List<Pig> pigs = new ArrayList<>();
            for (int i = 0; i < 30; i++) pigs.add(helper.spawn(EntityType.PIG, cell));
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(
                            pigs.stream().anyMatch(p -> p.getLastDamageSource() != null
                                    && p.getLastDamageSource().is(DamageTypes.CRAMMING)),
                            "no pig took cramming damage"))
                    .thenExecute(() -> pigs.forEach(Pig::discard))
                    .thenSucceed();
        });
    }
}
