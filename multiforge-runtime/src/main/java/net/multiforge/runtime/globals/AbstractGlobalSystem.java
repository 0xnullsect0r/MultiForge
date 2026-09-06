/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.globals;

import java.util.Objects;
import java.util.Set;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;

/**
 * Convenience base for {@link GlobalSystem} implementations — avoids
 * every one of the eight anticipated B2.x subsystems re-implementing the
 * same {@link #crossRegionEffect} delegation. Extending this class is
 * optional, not required by the interface.
 *
 * <p>{@link #crossRegionEffect} is {@code final} deliberately — a
 * subsystem needing a different cross-region strategy is a sign the
 * interface needs revisiting, not a reason to override. See {@code
 * docs/design/global-region.md} §3.7.
 *
 * <p>Default {@link #readSet()}/{@link #writeSet()} are empty; override
 * either when the subsystem's advisory declaration (§3.4) is
 * non-trivial.
 */
public abstract class AbstractGlobalSystem implements GlobalSystem {

    private final CrossRegionEffects effects;

    protected AbstractGlobalSystem(CrossRegionEffects effects) {
        this.effects = Objects.requireNonNull(effects, "effects");
    }

    @Override
    public final void crossRegionEffect(RegionId dest, Runnable task) {
        effects.enqueue(dest, task);
    }

    @Override
    public Set<WorldRef> readSet() {
        return Set.of();
    }

    @Override
    public Set<WorldRef> writeSet() {
        return Set.of();
    }
}
