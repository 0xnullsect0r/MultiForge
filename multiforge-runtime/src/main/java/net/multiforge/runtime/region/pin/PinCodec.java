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
package net.multiforge.runtime.region.pin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.multiforge.api.world.WorldRef;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * TOML codec for {@link RegionPin} — round-trips through
 * {@code multiforge-regions.toml}.
 *
 * <pre>
 *   [[pins]]
 *   id = "base-north"
 *   world = "minecraft:overworld"
 *   from = [ -128, -128 ]
 *   to   = [ 128, 128 ]
 * </pre>
 */
public final class PinCodec {

    private PinCodec() {}

    public static List<RegionPin> parse(String contents) {
        TomlParseResult r = Toml.parse(contents);
        if (r.hasErrors()) {
            String err = r.errors().stream().map(Object::toString).findFirst().orElse("<unknown>");
            throw new IllegalArgumentException("Invalid multiforge-regions.toml: " + err);
        }
        TomlArray pins = r.getArray("pins");
        if (pins == null) return List.of();
        List<RegionPin> out = new ArrayList<>(pins.size());
        for (int i = 0; i < pins.size(); i++) {
            TomlTable t = pins.getTable(i);
            String id = requireString(t, "id");
            WorldRef world = WorldRef.of(requireString(t, "world"));
            TomlArray from = t.getArray("from");
            TomlArray to = t.getArray("to");
            if (from == null || to == null || from.size() != 2 || to.size() != 2) {
                throw new IllegalArgumentException("pin '" + id + "' needs from = [x,z] and to = [x,z]");
            }
            out.add(new RegionPin(
                    id,
                    world,
                    Math.toIntExact(from.getLong(0)),
                    Math.toIntExact(from.getLong(1)),
                    Math.toIntExact(to.getLong(0)),
                    Math.toIntExact(to.getLong(1))));
        }
        return out;
    }

    public static String render(Collection<RegionPin> pins) {
        StringBuilder sb = new StringBuilder();
        sb.append("# MultiForge operator-pinned region rectangles.\n");
        sb.append("# Rewritten atomically by `/multiforge region pin` and\n");
        sb.append("# `/multiforge region unpin`. Manual edits are picked up at boot.\n\n");
        for (RegionPin p : pins) {
            sb.append("[[pins]]\n");
            sb.append("id = \"").append(escape(p.id())).append("\"\n");
            sb.append("world = \"").append(escape(p.world().dimensionId())).append("\"\n");
            sb.append("from = [ ")
                    .append(p.fromChunkX())
                    .append(", ")
                    .append(p.fromChunkZ())
                    .append(" ]\n");
            sb.append("to   = [ ")
                    .append(p.toChunkX())
                    .append(", ")
                    .append(p.toChunkZ())
                    .append(" ]\n\n");
        }
        return sb.toString();
    }

    private static String requireString(TomlTable t, String key) {
        String s = t.getString(key);
        if (s == null || s.isBlank()) throw new IllegalArgumentException("pin missing required key: " + key);
        return s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
