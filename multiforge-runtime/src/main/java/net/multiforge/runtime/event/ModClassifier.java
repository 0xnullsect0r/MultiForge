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
package net.multiforge.runtime.event;

import java.util.Objects;
import java.util.function.Function;

/**
 * Maps a listener's class to the {@link ModSafety} of the mod that defines
 * it. The fork binds the real lookup (mod file of the class's module, the
 * mod's declared safety, {@code config/multiforge-mods.toml}) once mods are
 * loaded; until then, and for classes of no mod, everything is {@link
 * ModSafety#HYBRID_SAFE}.
 */
public final class ModClassifier {

    private static volatile Function<Class<?>, ModSafety> lookup = c -> ModSafety.HYBRID_SAFE;

    private ModClassifier() {}

    public static void bind(Function<Class<?>, ModSafety> classifier) {
        lookup = Objects.requireNonNull(classifier, "classifier");
    }

    public static void reset() {
        lookup = c -> ModSafety.HYBRID_SAFE;
    }

    public static ModSafety safetyOf(Class<?> listenerClass) {
        ModSafety safety = lookup.apply(listenerClass);
        return safety == null ? ModSafety.HYBRID_SAFE : safety;
    }
}
