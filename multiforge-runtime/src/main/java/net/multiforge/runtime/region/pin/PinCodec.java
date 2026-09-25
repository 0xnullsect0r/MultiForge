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

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.multiforge.api.world.WorldRef;

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
        UnmodifiableConfig r;
        try {
            r = new TomlParser().parse(contents);
        } catch (ParsingException e) {
            throw new IllegalArgumentException("Invalid region pin file: " + e.getMessage(), e);
        }
        Object pinsRaw = r.get("pins");
        if (pinsRaw == null) return List.of();
        if (!(pinsRaw instanceof List<?> pins)) throw new IllegalArgumentException("'pins' must be an array of tables");
        List<RegionPin> out = new ArrayList<>(pins.size());
        for (Object entry : pins) {
            if (!(entry instanceof UnmodifiableConfig t)) {
                throw new IllegalArgumentException("'pins' must be an array of tables");
            }
            String id = requireString(t, "id");
            WorldRef world = WorldRef.of(requireString(t, "world"));
            int[] from = pair(t, "from", id);
            int[] to = pair(t, "to", id);
            out.add(new RegionPin(id, world, from[0], from[1], to[0], to[1]));
        }
        return out;
    }

    private static int[] pair(UnmodifiableConfig t, String key, String id) {
        if (!(t.get(key) instanceof List<?> xs) || xs.size() != 2) {
            throw new IllegalArgumentException("pin '" + id + "' needs from = [x,z] and to = [x,z]");
        }
        int[] out = new int[2];
        for (int i = 0; i < 2; i++) {
            if (!(xs.get(i) instanceof Number n) || xs.get(i) instanceof Double || xs.get(i) instanceof Float) {
                throw new IllegalArgumentException("pin '" + id + "': " + key + " must hold two integers");
            }
            out[i] = Math.toIntExact(n.longValue());
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

    private static String requireString(UnmodifiableConfig t, String key) {
        if (!(t.get(key) instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("pin missing required key: " + key);
        }
        return s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
