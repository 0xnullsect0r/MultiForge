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
package net.multiforge.api.world;

/**
 * Public identifier of a Minecraft dimension (Overworld, Nether, End, or
 * a datapack dimension). The API is intentionally minimal — a mod that
 * has a NeoForge {@code ServerLevel} in hand converts to a {@link
 * WorldRef} via the runtime binding.
 *
 * <p>Implementations are equal iff their {@link #dimensionId()} strings
 * are equal (namespace:path form, e.g. {@code "minecraft:overworld"}).
 */
public interface WorldRef {

    /**
     * @return the dimension's namespaced id (matches NeoForge {@code
     *         ResourceLocation}), e.g. {@code "minecraft:overworld"}.
     */
    String dimensionId();

    /** Convenience constructor for tests and for callers that only have an id. */
    static WorldRef of(String dimensionId) {
        return new Simple(dimensionId);
    }

    /** Package-private wrapper used by {@link #of(String)}. */
    record Simple(String dimensionId) implements WorldRef {
        public Simple {
            if (dimensionId == null || dimensionId.isBlank()) {
                throw new IllegalArgumentException("dimensionId must be non-blank");
            }
        }
    }
}
