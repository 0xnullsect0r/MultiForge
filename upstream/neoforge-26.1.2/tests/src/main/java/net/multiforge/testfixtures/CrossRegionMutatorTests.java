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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.region.RegionPhase;
import net.multiforge.runtime.region.RegionWorkerThread;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.capabilities.CapabilityListenerHolder;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTest;
import net.neoforged.testframework.gametest.GameTestPlayer;

/**
 * A block entity on one region's worker changing an entity another region is ticking, and
 * the audit fixes that had no test of their own. See {@code docs/design/shared-state-audit.md}.
 */
@ForEachTest(groups = "multiforge.crossregion")
public class CrossRegionMutatorTests {
    private static final int THREADS = 4;

    /** A mock player standing in a loaded chunk 2000 blocks from the test, in another region. */
    private record Far(MultiThreadedSchedulerHost host, ServerLevel level, BlockPos here, ChunkPos chunk, GameTestPlayer player) {
        void onNearWorker(Runnable task) {
            host.taskQueue().queueChunkTask(level.mfWorldRef(), here.getX() >> 4, here.getZ() >> 4, task);
        }

        boolean inPlace() {
            return ChunkPos.containing(player.blockPosition()).equals(chunk)
                    && host.regionizerFor(level.mfWorldRef()).regionAtChunk(chunk.x(), chunk.z()) != null;
        }
    }

    private static Far far(ExtendedGameTestHelper helper, MultiThreadedSchedulerHost host) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos at = here.offset(2000, 40, 0);
        ChunkPos chunk = ChunkPos.containing(at);
        Runnable release = TestForcedChunks.force(level, chunk);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        level.getServer().managedBlock(() -> level.isPositionEntityTicking(at) || System.nanoTime() > deadline);
        helper.assertTrue(level.isPositionEntityTicking(at), "the far chunk did not load within 60 s");
        GameTestPlayer player = helper.makeTickingMockServerPlayerInLevel(GameType.SURVIVAL);
        player.setNoGravity(true);
        player.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        helper.addEndListener(passed -> release.run());
        return new Far(host, level, here, chunk, player);
    }

    /**
     * Runs {@code onWorker} on the near region's worker. It reports a failure through its
     * argument; then {@code landed} must become true.
     */
    private static void scenario(DynamicTest test, java.util.function.Function<Far, Scenario> build) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            Far far = far(helper, host);
            AtomicReference<Scenario> scenario = new AtomicReference<>();
            AtomicReference<String> outcome = new AtomicReference<>();
            helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(far.inPlace(), "the player has not reached the far region"))
                    .thenExecute(() -> {
                        scenario.set(build.apply(far));
                        far.onNearWorker(() -> {
                            if (!(Thread.currentThread() instanceof RegionWorkerThread)) {
                                outcome.set("ran on " + Thread.currentThread().getName());
                                return;
                            }
                            List<String> problems = new ArrayList<>();
                            scenario.get().onWorker().accept(problems);
                            outcome.set(problems.isEmpty() ? "ok" : String.join("; ", problems));
                        });
                    })
                    .thenWaitUntil(() -> helper.assertTrue(outcome.get() != null, "worker task has not run"))
                    .thenExecute(() -> helper.assertTrue("ok".equals(outcome.get()), outcome.get()))
                    .thenWaitUntil(() -> helper.assertTrue(scenario.get().landed().getAsBoolean(), "the deferred change has not landed"))
                    .thenSucceed();
        });
    }

    private record Scenario(Consumer<List<String>> onWorker, java.util.function.BooleanSupplier landed) {}

    private static void check(List<String> problems, boolean ok, String problem) {
        if (!ok) problems.add(problem);
    }

    private static void inParallel(java.util.function.IntConsumer body) {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < THREADS; i++) {
            int index = i;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    body.accept(index);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "cross-region-test-" + i);
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        try {
            for (Thread thread : threads) thread.join();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        if (failure.get() != null) throw new IllegalStateException("a concurrent call failed: " + failure.get(), failure.get());
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "Concurrent attribute modifier changes lose nothing and leave the value and dirty set correct.")
    static void attributeModifiersSurviveConcurrentRegions(final DynamicTest test) {
        test.onGameTest(helper -> {
            int perThread = 400;
            for (int round = 0; round < 15; round++) {
                AttributeMap map = new AttributeMap(DefaultAttributes.getSupplier(EntityType.PIG));
                AttributeInstance instance = map.getInstance(Attributes.MAX_HEALTH);
                double base = instance.getBaseValue();
                int r = round;
                inParallel(t -> {
                    for (int i = 0; i < perThread; i++) {
                        instance.addTransientModifier(new AttributeModifier(
                                Identifier.fromNamespaceAndPath("multiforge", "audit_" + r + "_" + t + "_" + i),
                                0.25, // the total stays under max health's cap of 1024
                                AttributeModifier.Operation.ADD_VALUE));
                        instance.getValue();
                    }
                });
                int expected = THREADS * perThread;
                helper.assertTrue(instance.getModifiers().size() == expected, "kept " + instance.getModifiers().size() + " of " + expected + " modifiers");
                helper.assertTrue(instance.getValue() == base + expected * 0.25, "value " + instance.getValue() + ", expected " + (base + expected * 0.25));
                helper.assertTrue(map.getAttributesToUpdate().contains(instance), "the changed attribute is not queued for its owner");
            }
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = "Damage to an entity in another region lands after the barrier, and the caller gets true.")
    static void hurtOnAForeignEntityIsDeferred(final DynamicTest test) {
        scenario(test, far -> {
            float before = far.player().getHealth();
            return new Scenario(problems -> {
                boolean result = far.player().hurtServer(far.level(), far.level().damageSources().generic(), 2.0F);
                check(problems, result, "hurtServer returned false");
                check(problems, far.player().getHealth() == before, "the damage was applied from the wrong region's worker");
            }, () -> far.player().getHealth() < before);
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = "Adding to a foreign player's inventory empties the stack, returns true and lands after the barrier.")
    static void inventoryAddOnAForeignPlayerIsDeferred(final DynamicTest test) {
        scenario(test, far -> {
            Inventory inventory = far.player().getInventory();
            return new Scenario(problems -> {
                ItemStack stack = new ItemStack(Items.DIAMOND, 7);
                check(problems, inventory.add(stack), "add returned false");
                check(problems, stack.isEmpty(), "the caller's stack was not emptied");
                check(problems, inventory.countItem(Items.DIAMOND) == 0, "the item was added from the wrong region's worker");
            }, () -> inventory.countItem(Items.DIAMOND) == 7);
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = "What does not fit in a foreign player's full inventory drops at the player instead of vanishing.")
    static void inventoryOverflowOnAForeignPlayerDrops(final DynamicTest test) {
        scenario(test, far -> {
            Inventory inventory = far.player().getInventory();
            for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
                inventory.getNonEquipmentItems().set(slot, new ItemStack(Items.BEDROCK, 64));
            }
            return new Scenario(
                    problems -> check(problems, inventory.add(new ItemStack(Items.EMERALD, 3)), "add returned false"),
                    () -> far.level().getEntitiesOfClass(ItemEntity.class, far.player().getBoundingBox().inflate(8.0))
                            .stream()
                            .anyMatch(item -> item.getItem().is(Items.EMERALD) && item.getItem().getCount() == 3));
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = "Taking from a foreign player's inventory takes nothing and reports an empty result.")
    static void inventoryRemovalOnAForeignPlayerTakesNothing(final DynamicTest test) {
        scenario(test, far -> {
            Inventory inventory = far.player().getInventory();
            inventory.getNonEquipmentItems().set(3, new ItemStack(Items.GOLD_INGOT, 5));
            return new Scenario(problems -> {
                check(problems, inventory.removeItem(3, 2).isEmpty(), "removeItem returned items");
                check(problems, inventory.removeItemNoUpdate(3).isEmpty(), "removeItemNoUpdate returned items");
                check(problems, inventory.clearOrCountMatchingItems(s -> s.is(Items.GOLD_INGOT), 1, inventory) == 0, "clearOrCountMatchingItems removed items");
            }, () -> inventory.countItem(Items.GOLD_INGOT) == 5);
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = "Equipping a foreign entity lands after the barrier.")
    static void setItemSlotOnAForeignEntityIsDeferred(final DynamicTest test) {
        scenario(test, far -> new Scenario(problems -> {
            far.player().setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            check(problems, far.player().getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the item was equipped from the wrong region's worker");
        }, () -> far.player().getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)));
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = "Changing a foreign player's food lands after the barrier.")
    static void foodOnAForeignPlayerIsDeferred(final DynamicTest test) {
        scenario(test, far -> {
            int before = far.player().getFoodData().getFoodLevel();
            return new Scenario(problems -> {
                far.player().getFoodData().setFoodLevel(before - 7);
                check(problems, far.player().getFoodData().getFoodLevel() == before, "food was changed from the wrong region's worker");
            }, () -> far.player().getFoodData().getFoodLevel() == before - 7);
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = "Sending a foreign player's abilities waits for the barrier.")
    static void abilitiesUpdateOnAForeignPlayerIsDeferred(final DynamicTest test) {
        scenario(test, far -> {
            long[] before = new long[1];
            return new Scenario(problems -> {
                before[0] = ProbeRegistry.get("ServerPlayer.onUpdateAbilities:deferred-cross-region");
                far.player().onUpdateAbilities();
                check(problems, ProbeRegistry.get("ServerPlayer.onUpdateAbilities:deferred-cross-region") > before[0], "the update ran on the wrong region's worker");
            }, () -> true);
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 300)
    @TestHolder(description = "A kick from another region lands after the barrier.")
    static void kickFromARegionWaitsForTheBarrier(final DynamicTest test) {
        scenario(test, far -> new Scenario(problems -> {
            long before = ProbeRegistry.get("player.disconnect.deferred");
            far.player().connection.disconnect(Component.literal("audit test"));
            check(problems, ProbeRegistry.get("player.disconnect.deferred") > before, "the kick did not wait for the barrier");
            check(problems, !far.player().isRemoved(), "the player was removed while regions were ticking");
        }, () -> far.player().isRemoved()));
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 200)
    @TestHolder(description = "An entity's portal destination is worked out at the barrier, not on its region's worker.")
    static void portalDestinationWaitsForTheBarrier(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (MultiForgeRegionizedRuntime.current() == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            ServerLevel level = helper.getLevel();
            BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
            Pig pig = EntityType.PIG.create(level, EntitySpawnReason.TRIGGERED);
            helper.assertTrue(pig != null, "pig");
            pig.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
            pig.setNoAi(true);
            pig.setNoGravity(true);
            level.addFreshEntity(pig);
            helper.addEndListener(passed -> pig.discard());
            long before = ProbeRegistry.get("portal.deferred");
            helper.startSequence()
                    .thenExecute(() -> pig.setAsInsidePortal((Portal) Blocks.END_PORTAL, pos))
                    .thenWaitUntil(() -> helper.assertTrue(
                            ProbeRegistry.get("portal.deferred") > before, "the portal was not deferred to the barrier"))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = "A destroyed End crystal reaches the shared dragon fight at the barrier.")
    static void dragonCrystalCallbackWaitsForTheBarrier(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            ServerLevel level = helper.getLevel();
            ServerLevel end = level.getServer().getLevel(Level.END);
            EnderDragonFight fight = end == null ? null : end.getDragonFight();
            helper.assertTrue(fight != null, "the End has no dragon fight");
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            EndCrystal crystal = new EndCrystal(end, 0.5, 90.0, 0.5);
            AtomicReference<String> outcome = new AtomicReference<>();
            helper.startSequence()
                    .thenExecute(() -> host.taskQueue().queueChunkTask(level.mfWorldRef(), pos.getX() >> 4, pos.getZ() >> 4, () -> {
                        long before = ProbeRegistry.get("dragon.crystal.deferred");
                        fight.onCrystalDestroyed(crystal, end.damageSources().generic());
                        outcome.set(ProbeRegistry.get("dragon.crystal.deferred") > before ? "ok" : "the callback ran on a region worker");
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(outcome.get() != null, "worker task has not run"))
                    .thenExecute(() -> helper.assertTrue("ok".equals(outcome.get()), outcome.get()))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "Capability listeners added concurrently are all kept and all invalidated.")
    static void capabilityListenersSurviveConcurrentRegions(final DynamicTest test) {
        test.onGameTest(helper -> {
            int perThread = 2000;
            for (int round = 0; round < 10; round++) {
                CapabilityListenerHolder holder = new CapabilityListenerHolder();
                AtomicInteger invalidated = new AtomicInteger();
                List<ICapabilityInvalidationListener> strong = java.util.Collections.synchronizedList(new ArrayList<>());
                inParallel(t -> {
                    for (int i = 0; i < perThread; i++) {
                        ICapabilityInvalidationListener listener = () -> {
                            invalidated.incrementAndGet();
                            return true;
                        };
                        strong.add(listener);
                        // All in one chunk, on distinct blocks: the shared inner map is what races.
                        holder.addListener(new BlockPos(t, i % 300, (i / 300) % 16), listener);
                    }
                });
                holder.invalidateChunk(new ChunkPos(0, 0));
                int expected = THREADS * perThread;
                helper.assertTrue(invalidated.get() == expected, "invalidated " + invalidated.get() + " of " + expected + " listeners");
            }
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 400)
    @TestHolder(description = "A chunk promoted to ticking while workers run waits for the barrier, then ticks.")
    static void chunkPromotionWaitsForTheBarrier(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (MultiForgeRegionizedRuntime.current() == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            ServerLevel level = helper.getLevel();
            BlockPos here = helper.absolutePos(BlockPos.ZERO);
            ChunkPos fresh = new ChunkPos((here.getX() >> 4) + 700, (here.getZ() >> 4) + 700);
            AtomicReference<Runnable> release = new AtomicReference<>();
            helper.addEndListener(passed -> {
                if (release.get() != null) release.get().run();
            });
            helper.startSequence()
                    .thenExecute(() -> {
                        long before = ProbeRegistry.get("chunk.promotion.deferred");
                        RegionPhase.beginWorkers();
                        boolean deferred;
                        boolean tickedEarly;
                        try {
                            release.set(TestForcedChunks.force(level, fresh));
                            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                            level.getServer().managedBlock(() -> {
                                level.getChunkSource().pollTask();
                                return ProbeRegistry.get("chunk.promotion.deferred") > before
                                        || level.getChunkSource().isPositionTicking(fresh.pack())
                                        || System.nanoTime() > deadline;
                            });
                            deferred = ProbeRegistry.get("chunk.promotion.deferred") > before;
                            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(fresh.pack());
                            tickedEarly = holder != null && holder.getTickingChunk() != null;
                        } finally {
                            RegionPhase.endWorkers();
                        }
                        helper.assertTrue(deferred, "the promotion did not wait for the barrier");
                        helper.assertFalse(tickedEarly, "the chunk started ticking while workers ran");
                    })
                    .thenWaitUntil(() -> helper.assertTrue(
                            level.getChunkSource().isPositionTicking(fresh.pack()), "the chunk never started ticking"))
                    .thenSucceed();
        });
    }
}
