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
package net.multiforge.neoforge.world;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomSupport;

/**
 * A {@link RandomSource} with one {@code LegacyRandomSource} per thread.
 *
 * <p>{@code Level.random} is a {@code LegacyRandomSource}, which detects
 * use from two threads at once and throws ("Accessed LegacyRandomSource from
 * multiple threads"). Scheduled block ticks, random ticks and a great deal
 * of entity and block code draw from it, and region workers run that code
 * concurrently. The thread that built the level keeps the original stream,
 * so single-threaded behaviour (including {@link #setSeed}) is exactly
 * Vanilla's; every other thread draws from its own, independently seeded
 * stream.
 */
public final class PerThreadRandomSource implements RandomSource {
    private final Thread owner;
    private final RandomSource primary;
    private final ThreadLocal<RandomSource> others =
            ThreadLocal.withInitial(() -> RandomSource.create(RandomSupport.generateUniqueSeed()));

    private PerThreadRandomSource(RandomSource primary) {
        this.owner = Thread.currentThread();
        this.primary = primary;
    }

    /** Drop-in for {@code RandomSource.create()} in a {@code Level} field initialiser. */
    public static RandomSource create() {
        return new PerThreadRandomSource(RandomSource.create());
    }

    private RandomSource current() {
        return Thread.currentThread() == this.owner ? this.primary : this.others.get();
    }

    @Override
    public RandomSource fork() {
        return this.current().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return this.current().forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        this.current().setSeed(seed);
    }

    @Override
    public int nextInt() {
        return this.current().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return this.current().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return this.current().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return this.current().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return this.current().nextFloat();
    }

    @Override
    public double nextDouble() {
        return this.current().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return this.current().nextGaussian();
    }
}
