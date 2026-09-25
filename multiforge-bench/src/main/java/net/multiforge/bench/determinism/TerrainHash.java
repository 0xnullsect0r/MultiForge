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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.multiforge.bench.determinism.WorldDiff.NbtCompound;
import net.multiforge.bench.determinism.WorldDiff.NbtInt;
import net.multiforge.bench.determinism.WorldDiff.NbtList;
import net.multiforge.bench.determinism.WorldDiff.NbtString;
import net.multiforge.bench.determinism.WorldDiff.NbtTag;

/**
 * Per-chunk hash of a saved world's terrain: for every chunk saved at status
 * {@code minecraft:full}, its sections' block states and biomes, in canonical
 * NBT form.
 *
 * <p>This is the part of a world that a fixed seed determines. A whole save
 * is not reproducible even on stock NeoForge (timestamps, {@code
 * InhabitedTime}, entity UUIDs and the unseeded level random all differ run
 * to run — {@code docs/verification/m9/vanilla-baseline-analysis.md}), but
 * with random ticks, spawning, weather and fire off, the blocks and biomes of
 * a full chunk are fixed by world generation plus the scheduled block and
 * fluid ticks it queued, which the server then runs. Proto chunks are left
 * out: how far a chunk outside the loaded area got through generation
 * depends on timing. Palette order and the randomly rolled {@code age} of
 * growing-plant heads ({@link #RANDOM_AGE_BLOCKS}) are encoding and chance,
 * not terrain, and are normalised away.
 */
public final class TerrainHash {
    private TerrainHash() {}

    /** Chunk key ({@code <dimension> <x> <z>}) to the SHA-256 of its terrain, sorted. */
    public static TreeMap<String, String> compute(Path worldDir) throws IOException {
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Path> dim : regionDirs(worldDir).entrySet()) {
            try (Stream<Path> files = Files.list(dim.getValue())) {
                for (Path mca : files.filter(p -> p.getFileName().toString().endsWith(".mca"))
                        .sorted()
                        .toList()) {
                    hashRegionFile(dim.getKey(), mca, out);
                }
            }
        }
        return out;
    }

    /** One SHA-256 over every chunk's key and hash, in key order. */
    public static String digest(Map<String, String> chunks) {
        MessageDigest md = sha256();
        chunks.forEach((k, v) -> md.update((k + " " + v + "\n").getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(md.digest());
    }

    /** Keep only chunks with {@code |x| <= radius && |z| <= radius} (chunk coordinates). */
    public static TreeMap<String, String> within(Map<String, String> chunks, int radius) {
        TreeMap<String, String> out = new TreeMap<>();
        chunks.forEach((k, v) -> {
            String[] parts = k.split(" ");
            if (Math.abs(Integer.parseInt(parts[1])) <= radius && Math.abs(Integer.parseInt(parts[2])) <= radius) {
                out.put(k, v);
            }
        });
        return out;
    }

    public static void write(Map<String, String> chunks, Path file, String header) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (String line : header.split("\n")) w.write("# " + line + "\n");
            w.write("# digest " + digest(chunks) + "\n");
            for (Map.Entry<String, String> e : chunks.entrySet()) w.write(e.getKey() + " " + e.getValue() + "\n");
        }
    }

    public static TreeMap<String, String> read(Path file) throws IOException {
        TreeMap<String, String> out = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int cut = line.lastIndexOf(' ');
            out.put(line.substring(0, cut), line.substring(cut + 1));
        }
        return out;
    }

    /** Human-readable differences: chunks missing on either side and chunks whose terrain differs. */
    public static List<String> differences(Map<String, String> expected, Map<String, String> actual) {
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String got = actual.get(e.getKey());
            if (got == null) diffs.add("missing: " + e.getKey());
            else if (!got.equals(e.getValue())) diffs.add("differs: " + e.getKey());
        }
        for (String k : actual.keySet()) if (!expected.containsKey(k)) diffs.add("extra:   " + k);
        return diffs;
    }

    /** Dimension id to its {@code region/} directory, for the dimensions present in the save. */
    static Map<String, Path> regionDirs(Path worldDir) throws IOException {
        Map<String, Path> dirs = new TreeMap<>();
        addIfDir(dirs, "minecraft:overworld", worldDir.resolve("region"));
        addIfDir(dirs, "minecraft:the_nether", worldDir.resolve("DIM-1/region"));
        addIfDir(dirs, "minecraft:the_end", worldDir.resolve("DIM1/region"));
        Path custom = worldDir.resolve("dimensions");
        if (Files.isDirectory(custom)) {
            try (Stream<Path> walk = Files.walk(custom, 3)) {
                for (Path p : walk.filter(p -> p.getFileName().toString().equals("region"))
                        .toList()) {
                    Path rel = custom.relativize(p.getParent());
                    addIfDir(
                            dirs,
                            rel.getName(0) + ":"
                                    + rel.subpath(1, rel.getNameCount())
                                            .toString()
                                            .replace('\\', '/'),
                            p);
                }
            }
        }
        return dirs;
    }

    private static void addIfDir(Map<String, Path> dirs, String id, Path p) {
        if (Files.isDirectory(p)) dirs.put(id, p);
    }

    private static void hashRegionFile(String dim, Path mca, Map<String, String> out) throws IOException {
        byte[] bytes = Files.readAllBytes(mca);
        if (bytes.length < 8192) return;
        WorldDiff.ExternalChunks external = WorldDiff.externalChunksNextTo(mca);
        for (int slot = 0; slot < 1024; slot++) {
            int loc = readInt(bytes, slot * 4);
            int offset = (loc >>> 8) * 4096;
            if (loc == 0 || offset + 5 > bytes.length) continue;
            int length = readInt(bytes, offset);
            byte compression = bytes[offset + 4];
            byte[] raw;
            if ((compression & 0x80) != 0) {
                byte[] ext = external.load(slot);
                if (ext == null) throw new IOException("missing external chunk for slot " + slot + " of " + mca);
                raw = WorldDiff.decompressSlotPayload(compression & 0x7F, ext, 0, ext.length);
            } else {
                raw = WorldDiff.decompressSlotPayload(compression & 0x7F, bytes, offset + 5, length - 1);
            }
            if (raw == null) throw new IOException("undecodable chunk in slot " + slot + " of " + mca);
            NbtCompound root = WorldDiff.readNbtRoot(raw);
            if (root == null) throw new IOException("malformed chunk NBT in slot " + slot + " of " + mca);
            if (!(root.entries.get("Status") instanceof NbtString status)
                    || !status.value().equals("minecraft:full")) {
                continue;
            }
            int x = ((NbtInt) root.entries.get("xPos")).value();
            int z = ((NbtInt) root.entries.get("zPos")).value();
            out.put(dim + " " + x + " " + z, hashTerrain(root));
        }
    }

    /**
     * Hash a chunk's sections by content, not encoding: each paletted
     * container (4096 block states, 64 biomes) is unpacked to its per-entry
     * values, so two chunks with the same blocks hash alike even when their
     * palettes list the states in a different order — palette order is the
     * order states were first written, which depends on generation timing.
     */
    static String hashTerrain(NbtCompound chunk) {
        MessageDigest md = sha256();
        List<NbtCompound> sections = new ArrayList<>();
        if (chunk.entries.get("sections") instanceof NbtList list) {
            for (NbtTag tag : list.elements) sections.add((NbtCompound) tag);
        }
        sections.sort(java.util.Comparator.comparingInt(TerrainHash::sectionY));
        try {
            for (NbtCompound section : sections) {
                md.update(("Y" + sectionY(section) + "\n").getBytes(StandardCharsets.UTF_8));
                hashContainer(md, section.entries.get("block_states"), 4096, true);
                hashContainer(md, section.entries.get("biomes"), 64, false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static int sectionY(NbtCompound section) {
        NbtTag y = section.entries.get("Y");
        if (y instanceof WorldDiff.NbtByte b) return b.value();
        if (y instanceof NbtInt i) return i.value();
        return Integer.MIN_VALUE;
    }

    /** Feed the unpacked values of one paletted container, in index order, into {@code md}. */
    private static void hashContainer(MessageDigest md, NbtTag tag, int size, boolean blockStates) throws IOException {
        if (!(tag instanceof NbtCompound container) || !(container.entries.get("palette") instanceof NbtList palette)) {
            md.update((byte) 0);
            return;
        }
        byte[][] values = new byte[palette.elements.size()][];
        for (int i = 0; i < values.length; i++) values[i] = canonical(palette.elements.get(i));
        long[] data = container.entries.get("data") instanceof WorldDiff.NbtLongArray arr ? arr.value() : new long[0];
        if (values.length == 1 || data.length == 0) {
            md.update(values.length == 0 ? new byte[0] : values[0]);
            md.update(("x" + size).getBytes(StandardCharsets.UTF_8));
            return;
        }
        int bits = bitsFor(values.length, blockStates, data.length, size);
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < size; i++) {
            int index = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            md.update(index < values.length ? values[index] : new byte[] {(byte) 0xFF});
        }
    }

    /**
     * Bits per entry as Vanilla serialises them: block states use at least 4
     * bits, biomes exactly {@code ceil(log2(paletteSize))}. Checked against the
     * data length; on a mismatch (a mod's container strategy) the smallest
     * width that yields that length is used.
     */
    static int bitsFor(int paletteSize, boolean blockStates, int longs, int size) {
        int bits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        if (blockStates) bits = Math.max(4, bits);
        if (longsFor(bits, size) == longs) return bits;
        for (int b = 1; b <= 32; b++) if (longsFor(b, size) == longs) return b;
        return bits;
    }

    private static int longsFor(int bits, int size) {
        int perLong = 64 / bits;
        return (size + perLong - 1) / perLong;
    }

    /**
     * Growing-plant heads whose {@code age} Vanilla rolls from the unseeded
     * level random every time one is placed ({@code
     * GrowingPlantHeadBlock.getStateForPlacement}) — including when a block
     * update turns a body segment into a new head, e.g. water washing away
     * the vine below. The value differs run to run on stock NeoForge too, so
     * it is not part of the terrain.
     */
    static final java.util.Set<String> RANDOM_AGE_BLOCKS = java.util.Set.of(
            "minecraft:cave_vines", "minecraft:kelp", "minecraft:weeping_vines", "minecraft:twisting_vines");

    private static byte[] canonical(NbtTag entry) throws IOException {
        if (entry instanceof NbtString str) return (str.value() + "\n").getBytes(StandardCharsets.UTF_8);
        NbtCompound state = (NbtCompound) entry;
        if (state.entries.get("Name") instanceof NbtString name
                && RANDOM_AGE_BLOCKS.contains(name.value())
                && state.entries.get("Properties") instanceof NbtCompound props
                && props.entries.containsKey("age")) {
            NbtCompound stripped = new NbtCompound(new java.util.LinkedHashMap<>(props.entries));
            stripped.entries.remove("age");
            state = new NbtCompound(new java.util.LinkedHashMap<>(state.entries)).put("Properties", stripped);
        }
        return WorldDiff.writeCanonicalRoot(state);
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
