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
package net.multiforge.bench.determinism;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;
import net.multiforge.bench.determinism.WorldDiff.NbtCompound;
import net.multiforge.bench.determinism.WorldDiff.NbtInt;
import net.multiforge.bench.determinism.WorldDiff.NbtList;
import net.multiforge.bench.determinism.WorldDiff.NbtLong;
import net.multiforge.bench.determinism.WorldDiff.NbtString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerrainHashTest {

    private static NbtCompound chunk(int x, int z, String status, String block, long lastUpdate) {
        NbtCompound states = new NbtCompound();
        NbtList palette = new NbtList(WorldDiff.TAG_COMPOUND, new java.util.ArrayList<>());
        palette.elements.add(new NbtCompound().put("Name", new NbtString(block)));
        states.put("palette", palette);
        NbtCompound section = new NbtCompound()
                .put("Y", new WorldDiff.NbtByte((byte) 4))
                .put("block_states", states)
                .put("BlockLight", new WorldDiff.NbtByteArray(new byte[] {(byte) lastUpdate}));
        NbtList sections = new NbtList(WorldDiff.TAG_COMPOUND, new java.util.ArrayList<>(List.of(section)));
        return new NbtCompound()
                .put("xPos", new NbtInt(x))
                .put("zPos", new NbtInt(z))
                .put("Status", new NbtString(status))
                .put("LastUpdate", new NbtLong(lastUpdate))
                .put("InhabitedTime", new NbtLong(lastUpdate * 3))
                .put("sections", sections);
    }

    /** Write chunks into slots of world/region/r.0.0.mca (deflate), one sector each is enough here. */
    private static Path world(Path dir, NbtCompound... chunks) throws IOException {
        Path region = Files.createDirectories(dir.resolve("world/region"));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(new byte[8192]);
        byte[] header = new byte[8192];
        int sector = 2;
        for (NbtCompound c : chunks) {
            int x = ((NbtInt) c.entries.get("xPos")).value();
            int z = ((NbtInt) c.entries.get("zPos")).value();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DeflaterOutputStream out = new DeflaterOutputStream(bos)) {
                out.write(WorldDiff.writeCanonicalRoot(c));
            }
            byte[] payload = bos.toByteArray();
            byte[] sectorBytes = new byte[4096 * ((payload.length + 5 + 4095) / 4096)];
            int len = payload.length + 1;
            sectorBytes[0] = (byte) (len >>> 24);
            sectorBytes[1] = (byte) (len >>> 16);
            sectorBytes[2] = (byte) (len >>> 8);
            sectorBytes[3] = (byte) len;
            sectorBytes[4] = 2;
            System.arraycopy(payload, 0, sectorBytes, 5, payload.length);
            int slot = (x & 31) + (z & 31) * 32;
            int loc = (sector << 8) | (sectorBytes.length / 4096);
            header[slot * 4] = (byte) (loc >>> 24);
            header[slot * 4 + 1] = (byte) (loc >>> 16);
            header[slot * 4 + 2] = (byte) (loc >>> 8);
            header[slot * 4 + 3] = (byte) loc;
            sector += sectorBytes.length / 4096;
            file.write(sectorBytes);
        }
        byte[] bytes = file.toByteArray();
        System.arraycopy(header, 0, bytes, 0, header.length);
        Files.write(region.resolve("r.0.0.mca"), bytes);
        return dir.resolve("world");
    }

    @Test
    void timestampsAndLightDoNotAffectTheHash(@TempDir Path tmp) throws IOException {
        var a = TerrainHash.compute(world(tmp.resolve("a"), chunk(1, 2, "minecraft:full", "minecraft:stone", 10)));
        var b = TerrainHash.compute(world(tmp.resolve("b"), chunk(1, 2, "minecraft:full", "minecraft:stone", 99)));
        assertThat(a).containsOnlyKeys("minecraft:overworld 1 2");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void blockChangesDo(@TempDir Path tmp) throws IOException {
        var a = TerrainHash.compute(world(tmp.resolve("a"), chunk(1, 2, "minecraft:full", "minecraft:stone", 10)));
        var b = TerrainHash.compute(world(tmp.resolve("b"), chunk(1, 2, "minecraft:full", "minecraft:water", 10)));
        assertThat(TerrainHash.differences(a, b)).containsExactly("differs: minecraft:overworld 1 2");
    }

    /** A two-state section: {@code first} everywhere except index 5, which holds {@code second}. */
    private static NbtCompound twoStateChunk(String[] palette, int firstIndex) {
        NbtList pal = new NbtList(WorldDiff.TAG_COMPOUND, new java.util.ArrayList<>());
        for (String name : palette) pal.elements.add(new NbtCompound().put("Name", new NbtString(name)));
        long[] data = new long[256]; // 4 bits per entry, 16 per long
        for (int i = 0; i < 4096; i++) {
            long v = i == 5 ? 1 - firstIndex : firstIndex;
            data[i / 16] |= v << ((i % 16) * 4);
        }
        NbtCompound states = new NbtCompound().put("palette", pal).put("data", new WorldDiff.NbtLongArray(data));
        NbtCompound section =
                new NbtCompound().put("Y", new WorldDiff.NbtByte((byte) 0)).put("block_states", states);
        return new NbtCompound()
                .put("sections", new NbtList(WorldDiff.TAG_COMPOUND, new java.util.ArrayList<>(List.of(section))));
    }

    @Test
    void paletteOrderDoesNotAffectTheHash() {
        String a = TerrainHash.hashTerrain(twoStateChunk(new String[] {"minecraft:stone", "minecraft:dirt"}, 0));
        String b = TerrainHash.hashTerrain(twoStateChunk(new String[] {"minecraft:dirt", "minecraft:stone"}, 1));
        String c = TerrainHash.hashTerrain(twoStateChunk(new String[] {"minecraft:dirt", "minecraft:stone"}, 0));
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }

    @Test
    void theRandomAgeOfAVineHeadIsNotTerrain() {
        java.util.function.IntFunction<String> vine = age -> {
            NbtCompound props = new NbtCompound()
                    .put("age", new NbtString(String.valueOf(age)))
                    .put("berries", new NbtString("false"));
            NbtCompound state = new NbtCompound()
                    .put("Name", new NbtString("minecraft:cave_vines"))
                    .put("Properties", props);
            NbtList pal = new NbtList(WorldDiff.TAG_COMPOUND, new java.util.ArrayList<>(List.of(state)));
            NbtCompound section = new NbtCompound()
                    .put("Y", new WorldDiff.NbtByte((byte) 0))
                    .put("block_states", new NbtCompound().put("palette", pal));
            return TerrainHash.hashTerrain(new NbtCompound()
                    .put("sections", new NbtList(WorldDiff.TAG_COMPOUND, new java.util.ArrayList<>(List.of(section)))));
        };
        assertThat(vine.apply(3)).isEqualTo(vine.apply(22));
    }

    @Test
    void bitWidthsFollowVanilla() {
        assertThat(TerrainHash.bitsFor(2, true, 256, 4096)).isEqualTo(4);
        assertThat(TerrainHash.bitsFor(17, true, 342, 4096)).isEqualTo(5);
        assertThat(TerrainHash.bitsFor(3, false, 2, 64)).isEqualTo(2);
    }

    @Test
    void protoChunksAreLeftOut(@TempDir Path tmp) throws IOException {
        var hashes = TerrainHash.compute(world(
                tmp,
                chunk(0, 0, "minecraft:full", "minecraft:stone", 1),
                chunk(3, 0, "minecraft:features", "minecraft:stone", 1)));
        assertThat(hashes).containsOnlyKeys("minecraft:overworld 0 0");
    }

    @Test
    void baselineFilesRoundTrip(@TempDir Path tmp) throws IOException {
        TreeMap<String, String> chunks =
                new TreeMap<>(Map.of("minecraft:overworld 0 0", "ab", "minecraft:overworld 1 -1", "cd"));
        Path file = tmp.resolve("baseline.txt");
        TerrainHash.write(chunks, file, "test\nsecond line");
        assertThat(TerrainHash.read(file)).isEqualTo(chunks);
        assertThat(TerrainHash.within(chunks, 0)).containsOnlyKeys("minecraft:overworld 0 0");
        assertThat(TerrainHash.differences(
                        chunks, Map.of("minecraft:overworld 0 0", "ab", "minecraft:overworld 5 5", "ef")))
                .containsExactly("missing: minecraft:overworld 1 -1", "extra:   minecraft:overworld 5 5");
    }
}
