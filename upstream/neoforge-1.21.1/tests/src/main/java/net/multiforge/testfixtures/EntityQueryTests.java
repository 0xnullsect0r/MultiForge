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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.multiforge.neoforge.world.LockingEntityGetter;
import net.multiforge.runtime.region.RegionPhase;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * GameTests for the entity query path ({@link LockingEntityGetter}): the
 * server-thread fast path is Vanilla's getter itself, and the locked path
 * (region workers, other threads) returns Vanilla's entities in Vanilla's
 * order, aborts where Vanilla would, and allocates nothing per query once its
 * per-thread buffers have grown.
 *
 * <p>A getter built here without a bound server thread always takes the
 * locked path, so both paths are exercised from the GameTest (server) thread.
 */
@ForEachTest(groups = "multiforge.entities")
public class EntityQueryTests {
    private static void spawnMix(GameTestHelper helper) {
        for (int i = 0; i < 6; i++) {
            Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(i % 3, 2, i / 3));
            pig.setNoGravity(true);
            Chicken chicken = helper.spawnWithNoFreeWill(EntityType.CHICKEN, new BlockPos(i % 3, 2, 2 - i / 3));
            chicken.setNoGravity(true);
        }
    }

    private static AABB area(GameTestHelper helper) {
        return helper.getBounds().inflate(1.0);
    }

    private static <U extends Entity> List<U> collect(
            LevelEntityGetter<Entity> getter, EntityTypeTest<Entity, U> test, AABB bounds, int limit) {
        List<U> out = new ArrayList<>();
        getter.get(test, bounds, e -> {
            out.add(e);
            return out.size() >= limit
                    ? AbortableIterationConsumer.Continuation.ABORT
                    : AbortableIterationConsumer.Continuation.CONTINUE;
        });
        return out;
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "The locked entity-query path returns Vanilla's entities in Vanilla's order,",
            "stops where Vanilla stops, and survives a query nested in a consumer."
    })
    static void lockedQueriesMatchVanilla(final DynamicTest test) {
        test.onGameTest(helper -> {
            ServerLevel level = helper.getLevel();
            spawnMix(helper);
            helper.startSequence()
                    .thenExecuteAfter(2, () -> {
                        LockingEntityGetter<Entity> levelGetter = (LockingEntityGetter<Entity>) level.mfEntityManager().getEntityGetter();
                        LevelEntityGetter<Entity> vanilla = levelGetter.vanillaGetter();
                        LockingEntityGetter<Entity> locked = new LockingEntityGetter<>(vanilla, new Object());
                        helper.assertFalse(locked.isFastPath(), "an unbound getter took the fast path");
                        if (LockingEntityGetter.lockFreeOutsidePhase() && !RegionPhase.workersInFlight()) {
                            helper.assertTrue(levelGetter.isFastPath(), "the level's getter is locked on its own server thread");
                        }
                        AABB box = area(helper);

                        List<Entity> expected = new ArrayList<>();
                        vanilla.get(box, expected::add);
                        List<Entity> actual = new ArrayList<>();
                        locked.get(box, actual::add);
                        helper.assertTrue(expected.size() >= 12, "expected at least 12 entities, Vanilla found " + expected.size());
                        helper.assertTrue(actual.equals(expected), "bounds query differs from Vanilla: " + actual + " vs " + expected);

                        EntityTypeTest<Entity, Pig> pigs = EntityTypeTest.forClass(Pig.class);
                        helper.assertTrue(collect(locked, pigs, box, 3).equals(collect(vanilla, pigs, box, 3)),
                                "aborting typed query differs from Vanilla");
                        helper.assertTrue(collect(locked, pigs, box, Integer.MAX_VALUE).equals(collect(vanilla, pigs, box, Integer.MAX_VALUE)),
                                "typed query differs from Vanilla");

                        List<Pig> allExpected = new ArrayList<>();
                        vanilla.get(pigs, p -> {
                            allExpected.add(p);
                            return AbortableIterationConsumer.Continuation.CONTINUE;
                        });
                        List<Pig> allActual = new ArrayList<>();
                        locked.get(pigs, p -> {
                            allActual.add(p);
                            return AbortableIterationConsumer.Continuation.CONTINUE;
                        });
                        helper.assertTrue(allActual.equals(allExpected), "level-wide typed query differs from Vanilla");

                        Entity first = expected.get(0);
                        helper.assertTrue(locked.get(first.getId()) == first, "get(id) differs from Vanilla");
                        helper.assertTrue(locked.get(first.getUUID()) == first, "get(uuid) differs from Vanilla");
                        helper.assertTrue(locked.get(UUID.randomUUID()) == null, "get(unknown uuid) is not null");
                        List<Entity> all = new ArrayList<>();
                        locked.getAll().forEach(all::add);
                        List<Entity> allVanilla = new ArrayList<>();
                        vanilla.getAll().forEach(allVanilla::add);
                        helper.assertTrue(all.equals(allVanilla), "getAll differs from Vanilla");

                        // Re-entrancy: each consumer call runs a nested query on the same thread.
                        List<Entity> outer = new ArrayList<>();
                        List<Integer> innerSizes = new ArrayList<>();
                        locked.get(box, e -> {
                            outer.add(e);
                            List<Entity> inner = new ArrayList<>();
                            locked.get(box, inner::add);
                            innerSizes.add(inner.size());
                        });
                        helper.assertTrue(outer.equals(expected), "a nested query disturbed the outer one");
                        helper.assertTrue(innerSizes.stream().allMatch(n -> n == expected.size()), "nested queries saw " + innerSizes);
                    })
                    .thenExecute(() -> {
                        for (Entity e : level.getEntitiesOfClass(Entity.class, area(helper), e -> !(e instanceof net.minecraft.world.entity.player.Player))) e.discard();
                    })
                    .thenSucceed();
        });
    }

    /** A getter over a fixed array that allocates nothing, so any allocation measured is the wrapper's. */
    private static final class ArrayGetter implements LevelEntityGetter<Entity> {
        private final Entity[] entities;

        ArrayGetter(Entity[] entities) {
            this.entities = entities;
        }

        @Override
        public Entity get(int id) {
            for (Entity e : this.entities) if (e.getId() == id) return e;
            return null;
        }

        @Override
        public Entity get(UUID uuid) {
            for (Entity e : this.entities) if (e.getUUID().equals(uuid)) return e;
            return null;
        }

        @Override
        public Iterable<Entity> getAll() {
            return List.of(this.entities);
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AbortableIterationConsumer<U> consumer) {
            for (Entity e : this.entities) {
                U u = test.tryCast(e);
                if (u != null && consumer.accept(u).shouldAbort()) return;
            }
        }

        @Override
        public void get(AABB bounds, Consumer<Entity> consumer) {
            for (Entity e : this.entities) {
                if (e.getBoundingBox().intersects(bounds)) consumer.accept(e);
            }
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AABB bounds, AbortableIterationConsumer<U> consumer) {
            for (Entity e : this.entities) {
                U u = test.tryCast(e);
                if (u != null && u.getBoundingBox().intersects(bounds) && consumer.accept(u).shouldAbort()) return;
            }
        }
    }

    /** Counts matches without allocating. */
    private static final class Counter implements AbortableIterationConsumer<Entity> {
        long n;

        @Override
        public AbortableIterationConsumer.Continuation accept(Entity e) {
            this.n++;
            return AbortableIterationConsumer.Continuation.CONTINUE;
        }
    }

    private static final class PlainCounter implements Consumer<Entity> {
        long n;

        @Override
        public void accept(Entity e) {
            this.n++;
        }
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "A locked entity query allocates nothing once its per-thread buffers have",
            "grown (measured with ThreadMXBean.getCurrentThreadAllocatedBytes)."
    })
    static void lockedQueriesAllocateNothing(final DynamicTest test) {
        test.onGameTest(helper -> {
            spawnMix(helper);
            helper.startSequence()
                    .thenExecuteAfter(2, () -> {
                        List<Entity> found = helper.getLevel().getEntitiesOfClass(Entity.class, area(helper),
                                e -> e instanceof Pig || e instanceof Chicken);
                        LockingEntityGetter<Entity> locked = new LockingEntityGetter<>(new ArrayGetter(found.toArray(new Entity[0])), new Object());
                        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                        helper.assertTrue(bean.isThreadAllocatedMemorySupported(), "allocation accounting unsupported");
                        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
                        AABB box = area(helper);
                        EntityTypeTest<Entity, Entity> any = EntityTypeTest.forClass(Entity.class);
                        Counter counter = new Counter();
                        PlainCounter plain = new PlainCounter();
                        int queries = 20_000;
                        for (int i = 0; i < queries; i++) { // warm-up: buffers grow, code compiles
                            locked.get(any, box, counter);
                            locked.get(box, plain);
                            locked.get(any, counter);
                        }
                        long before = bean.getCurrentThreadAllocatedBytes();
                        for (int i = 0; i < queries; i++) {
                            locked.get(any, box, counter);
                            locked.get(box, plain);
                            locked.get(any, counter);
                        }
                        long allocated = bean.getCurrentThreadAllocatedBytes() - before;
                        helper.assertTrue(counter.n > 0 && plain.n > 0, "the queries found nothing");
                        // One-off allocations (a safepoint, a lazily resolved call site) are tolerated;
                        // a per-query allocation would be at least 16 bytes × 3 × 20,000.
                        helper.assertTrue(allocated < 4096, "locked queries allocated " + allocated + " bytes over " + (3 * queries) + " queries");
                    })
                    .thenExecute(() -> {
                        for (Entity e : helper.getLevel().getEntitiesOfClass(Entity.class, area(helper), e -> e instanceof Pig || e instanceof Chicken)) e.discard();
                    })
                    .thenSucceed();
        });
    }
}
