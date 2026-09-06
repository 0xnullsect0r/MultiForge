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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.InflaterInputStream;

/**
 * File-level SHA-256 comparison of two world directories, tolerating a
 * small allowlist of file names known to carry wall-clock or session
 * metadata that legitimately differ between runs of the same seed.
 *
 * <p>See {@link DeterminismHarness} for the M7 scope note on why
 * byte-identical comparison is legitimate here.
 */
public final class WorldDiff {

    /**
     * File names known to contain wall-clock timestamps, session ids, or
     * other run-to-run metadata that are expected to differ between two
     * runs of the same fixed seed and tick count. Matched by exact
     * base file name (not full path).
     */
    static final Set<String> NON_DETERMINISTIC_NAMES =
            Set.of("session.lock", "session.lock.old", "raids.dat", "raids.dat_old", "stats", "advancements");

    /**
     * Suffixes on top of the exact-name allowlist above. Vanilla writes
     * {@code .dat_old} copies of every save file every autosave — these
     * are stale byte-for-byte snapshots from the *previous* tick and
     * differ across identical-seed reruns depending on when the harness
     * captured the world dir. Matches per /67 review finding #13.
     */
    static final Set<String> NON_DETERMINISTIC_SUFFIXES = Set.of(".dat_old");

    private WorldDiff() {}

    public static Result compare(Path baselineRoot, Path patchedRoot) {
        return compare(baselineRoot, patchedRoot, DiffMode.BYTE_IDENTICAL);
    }

    /**
     * Compare two world directories under the requested {@link DiffMode}.
     * {@code BYTE_IDENTICAL} is the M7 single-worker check; {@code
     * SEMANTIC} is the M8/M9 N-worker check — see {@link DiffMode} for
     * the full contract. Only {@code .mca} region files are affected by
     * the mode; every other file is still hashed verbatim regardless.
     */
    public static Result compare(Path baselineRoot, Path patchedRoot, DiffMode mode) {
        TreeMap<String, String> baselineHashes = hashTree(baselineRoot, mode);
        TreeMap<String, String> patchedHashes = hashTree(patchedRoot, mode);
        return new Result(baselineRoot, patchedRoot, baselineHashes, patchedHashes);
    }

    private static TreeMap<String, String> hashTree(Path root, DiffMode mode) {
        TreeMap<String, String> out = new TreeMap<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    if (NON_DETERMINISTIC_NAMES.contains(name)) {
                        return FileVisitResult.CONTINUE;
                    }
                    for (String suffix : NON_DETERMINISTIC_SUFFIXES) {
                        if (name.endsWith(suffix)) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    String rel = root.relativize(file).toString();
                    out.put(rel, hashFile(file, name, mode));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (NON_DETERMINISTIC_NAMES.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(file));
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * File dispatcher: {@code .mca} region files get canonically hashed
     * per-chunk-slot rather than as raw bytes, so identical-seed reruns
     * that write the same chunks in a different order (and therefore land
     * at different sector offsets in the location table) still compare
     * equal. Every other file is hashed verbatim.
     *
     * <p>Anvil (MCA) format:
     * <ul>
     *   <li>bytes 0–4095: location table, 1024 big-endian entries of
     *       {@code (sectorOffset << 8) | sectorCount}; all-zero = slot
     *       empty. Non-deterministic across reruns when chunks defrag
     *       or grow past their prior sector count.
     *   <li>bytes 4096–8191: timestamp table, 1024 big-endian
     *       {@code SecondsSinceEpoch} entries. Wall-clock-derived, so
     *       always differs across reruns.
     *   <li>bytes 8192+: chunk payload data at sector-aligned offsets;
     *       each starts with a big-endian 32-bit length + 1 compression
     *       byte + compressed payload.
     * </ul>
     *
     * <p>We iterate 1024 chunk slots in fixed order; for each occupied
     * slot we hash {@code (slotIndex, payloadBytes)} where
     * {@code payloadBytes} is the length-prefixed chunk body read from
     * its own declared length (not the sector-count in the location
     * table). Empty slots and malformed entries are silently skipped —
     * an empty slot in one run vs. an occupied slot in the other still
     * causes a hash mismatch through the slotIndex prefix, so real
     * chunk-loss regressions are caught.
     */
    static String hashFile(Path file, String name) {
        return hashFile(file, name, DiffMode.BYTE_IDENTICAL);
    }

    /**
     * Mode-aware sibling of {@link #hashFile(Path, String)}: dispatches
     * {@code .mca} region files to {@link #canonicalMcaHash(byte[],
     * DiffMode)} under the requested {@code mode}; every other file is
     * still hashed verbatim (mode has no meaning outside Anvil region
     * files).
     */
    static String hashFile(Path file, String name, DiffMode mode) {
        if (!name.endsWith(".mca")) return sha256(file);
        try {
            byte[] bytes = Files.readAllBytes(file);
            return canonicalMcaHash(bytes, mode);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String canonicalMcaHash(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // Files smaller than the location table can't be a valid MCA — hash as-is
            // so a truncated file still produces a stable but distinctive hash.
            if (bytes.length < 4096) {
                md.update(bytes);
                return toHex(md.digest());
            }
            for (int slot = 0; slot < 1024; slot++) {
                int loc = readBigEndianInt(bytes, slot * 4);
                if (loc == 0) {
                    // Truly empty slot — the "no data present" sentinel below is what
                    // distinguishes this from a MALFORMED-but-nonzero slot.
                    md.update(SLOT_EMPTY_SENTINEL);
                    continue;
                }
                // long arithmetic throughout so a 24-bit sectorOffset × 4096 = up to 36 bits
                // does NOT wrap to a negative int and defeat the bounds checks. /67 round-3
                // finding: prior signed-int arithmetic crashed the whole harness on any
                // adversarial/corrupt input (single bad .mca killed the diff run).
                int sectorOffset = loc >>> 8; // 24 bits
                int sectorCount = loc & 0xFF; // 8 bits, 0..255
                long payloadStartL = (long) sectorOffset * 4096L;
                if (sectorOffset < 2 || sectorCount == 0 || payloadStartL + 5L > (long) bytes.length) {
                    // Nonzero-but-malformed slot: hash the raw location entry so run A's
                    // "slot 5 = 0x00000001" is DISTINGUISHABLE from run B's "slot 5 = 0".
                    // Prior code fell through to the same `continue` as empty → silent
                    // MATCH on corruption regressions.
                    hashMalformedSlot(md, slot, loc);
                    continue;
                }
                int payloadStart = (int) payloadStartL; // safe: bounded by bytes.length < Integer.MAX_VALUE
                int chunkLength = readBigEndianInt(bytes, payloadStart); // includes the 1 compression byte
                // Guard the second overflow site: `payloadStart + 4 + chunkLength` also wraps
                // to a negative int for chunkLength ≥ ~2 GiB. Same crash pattern.
                if (chunkLength <= 0 || (long) chunkLength > (long) bytes.length - (long) payloadStart - 4L) {
                    hashMalformedSlot(md, slot, loc);
                    continue;
                }
                // Feed slot index (fixed order) + payload bytes (length-prefixed).
                md.update(slotIndexBytes(slot));
                md.update(bytes, payloadStart, 4 + chunkLength);
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Two hashing strategies for {@code .mca} region files.
     *
     * <p>{@link #BYTE_IDENTICAL} is the M7 mode: strip the volatile MCA
     * header (sector-offset + timestamp tables), then feed the raw
     * per-slot payload bytes into SHA-256 in fixed slot order. Rejects any
     * chunk-level payload difference — sufficient under {@code --workers=1}
     * where the tick body writes chunks in a deterministic order.
     *
     * <p>{@link #SEMANTIC} is the M8/M9 mode: additionally parse each
     * chunk payload as NBT, canonicalise the volatile ordering (entity /
     * block-entity / ticker list order) and strip a small set of fields
     * that legitimately drift under N-worker parallelism ({@code Motion}
     * under an idle threshold, {@code Air}, {@code HurtTime}, {@code
     * DeathTime}, {@code PortalCooldown}, per-thread random-state
     * scratch), then re-serialise to a canonical byte order (compound
     * keys sorted ascending) before hashing. See {@code
     * docs/design/nbt-semantic-diff.md} for the full contract.
     *
     * <p>Byte-identical is a strict subset of semantic: any pair that
     * matches byte-identical also matches semantic.
     */
    public enum DiffMode {
        BYTE_IDENTICAL,
        SEMANTIC
    }

    /**
     * Canonical MCA hash of {@code mcaFile} under the requested {@code
     * mode}. See {@link DiffMode}. Reads the whole file into memory and
     * dispatches to the byte-buffer overload — MCA files are small
     * enough (a region caps at ~8 MiB) that streaming buys nothing.
     *
     * @throws UncheckedIOException on read failure — matches the failure
     *     shape of every other path in {@link WorldDiff}.
     */
    public static String canonicalMcaHash(Path mcaFile, DiffMode mode) {
        try {
            return canonicalMcaHash(Files.readAllBytes(mcaFile), mode);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Canonical MCA hash of {@code bytes} under the requested {@code
     * mode}. See {@link DiffMode}. The zero-arg overload
     * {@link #canonicalMcaHash(byte[])} continues to work and is
     * equivalent to passing {@link DiffMode#BYTE_IDENTICAL}.
     */
    public static String canonicalMcaHash(byte[] bytes, DiffMode mode) {
        return switch (mode) {
            case BYTE_IDENTICAL -> canonicalMcaHash(bytes);
            case SEMANTIC -> semanticMcaHash(bytes);
        };
    }

    /**
     * Semantic-diff sibling of {@link #canonicalMcaHash(byte[])}. Same
     * outer loop over the 1024 chunk slots, same
     * empty-slot/malformed-slot fallbacks, but for each occupied slot
     * decompresses the payload, parses NBT, applies the normalisation
     * rules from {@code docs/design/nbt-semantic-diff.md}, and hashes
     * the canonical re-serialisation instead of the raw payload bytes.
     *
     * <p>Fail-soft on NBT parse or decompress failure — the raw payload
     * bytes are hashed instead, so the slot is still deterministic and
     * a real chunk-corruption regression still trips the diff (just
     * without the semantic-normalise pass).
     */
    static String semanticMcaHash(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (bytes.length < 4096) {
                md.update(bytes);
                return toHex(md.digest());
            }
            for (int slot = 0; slot < 1024; slot++) {
                int loc = readBigEndianInt(bytes, slot * 4);
                if (loc == 0) {
                    md.update(SLOT_EMPTY_SENTINEL);
                    continue;
                }
                int sectorOffset = loc >>> 8;
                int sectorCount = loc & 0xFF;
                long payloadStartL = (long) sectorOffset * 4096L;
                if (sectorOffset < 2 || sectorCount == 0 || payloadStartL + 5L > (long) bytes.length) {
                    hashMalformedSlot(md, slot, loc);
                    continue;
                }
                int payloadStart = (int) payloadStartL;
                int chunkLength = readBigEndianInt(bytes, payloadStart);
                if (chunkLength <= 0 || (long) chunkLength > (long) bytes.length - (long) payloadStart - 4L) {
                    hashMalformedSlot(md, slot, loc);
                    continue;
                }
                md.update(slotIndexBytes(slot));
                byte[] canonical = tryCanonicaliseSlotPayload(bytes, payloadStart, chunkLength);
                if (canonical != null) {
                    md.update(SLOT_SEMANTIC_TAG);
                    md.update(canonical);
                } else {
                    // NBT parse or decompress failed — fall back to the raw slot bytes so the
                    // slot is still deterministic. Distinguish with a raw-tag prefix so a
                    // parse-failure hash never collides with a canonical-form hash.
                    md.update(SLOT_RAW_FALLBACK_TAG);
                    md.update(bytes, payloadStart, 4 + chunkLength);
                }
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Decompress + parse + normalise + canonical-serialise one slot's
     * payload. Returns {@code null} on any failure so the caller can
     * fall back to raw-bytes hashing.
     */
    private static byte[] tryCanonicaliseSlotPayload(byte[] bytes, int payloadStart, int chunkLength) {
        try {
            byte compressionByte = bytes[payloadStart + 4];
            int compressionType = compressionByte & 0x7F; // strip external-stream MSB
            int compressedStart = payloadStart + 5;
            int compressedLen = chunkLength - 1; // chunkLength includes the compression byte
            if (compressedLen < 0) return null;
            byte[] raw = decompressSlotPayload(compressionType, bytes, compressedStart, compressedLen);
            if (raw == null) return null;
            NbtCompound root = readNbtRoot(raw);
            if (root == null) return null;
            normaliseCompound(root);
            return writeCanonicalRoot(root);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Decompress a chunk payload. Matches Vanilla {@code
     * RegionFileVersion} numeric ids for deflate (2) and none (3). Gzip
     * (1) is uncommon in practice for chunk payloads (Vanilla writes 2);
     * skip it here to keep the WorldDiff module dependency-free — the
     * raw-payload fallback will still hash deterministically.
     *
     * <p>Returns {@code null} on unknown compression or any inflate
     * error so the caller falls back to raw hashing.
     */
    private static byte[] decompressSlotPayload(int compressionType, byte[] bytes, int off, int len) {
        if (compressionType == 3) {
            // COMPRESSION_NONE — payload is the NBT tree directly.
            byte[] out = new byte[len];
            System.arraycopy(bytes, off, out, 0, len);
            return out;
        }
        if (compressionType != 2) {
            // COMPRESSION_DEFLATE (2) is the only inflate we support here. Others fall through.
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes, off, len);
                InflaterInputStream iis = new InflaterInputStream(bais)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = iis.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Semantic-mode canonical-form marker; distinguishes from raw fallback. */
    private static final byte[] SLOT_SEMANTIC_TAG = {(byte) 0x5E, (byte) 0x1A, (byte) 0x17, (byte) 0x1C};

    /** Semantic-mode raw-fallback marker; used when NBT parse fails on a slot. */
    private static final byte[] SLOT_RAW_FALLBACK_TAG = {(byte) 0xFA, (byte) 0x11, (byte) 0xBA, (byte) 0xCC};

    // ---------------------------------------------------------------------
    // Minimal NBT model + canonical writer + normalisation
    // ---------------------------------------------------------------------
    //
    // Bench module is MC-free (no net.minecraft.nbt on the classpath), so we
    // carry a small hand-rolled NBT reader/writer that covers the tag types
    // Vanilla 1.21.1 emits in chunk NBT. Wire format matches Java's
    // DataInput/DataOutput as documented at
    // https://minecraft.wiki/w/NBT_format ; sufficient for the M8 semantic
    // acceptance harness, not a general-purpose NBT lib.

    static final byte TAG_END = 0;
    static final byte TAG_BYTE = 1;
    static final byte TAG_SHORT = 2;
    static final byte TAG_INT = 3;
    static final byte TAG_LONG = 4;
    static final byte TAG_FLOAT = 5;
    static final byte TAG_DOUBLE = 6;
    static final byte TAG_BYTE_ARRAY = 7;
    static final byte TAG_STRING = 8;
    static final byte TAG_LIST = 9;
    static final byte TAG_COMPOUND = 10;
    static final byte TAG_INT_ARRAY = 11;
    static final byte TAG_LONG_ARRAY = 12;

    /**
     * Sealed root of the NBT tag hierarchy. Package-private so
     * {@code WorldDiffSemanticTest} can construct fixture trees directly
     * without going through a compressed payload round-trip.
     */
    sealed interface NbtTag
            permits NbtByte,
                    NbtShort,
                    NbtInt,
                    NbtLong,
                    NbtFloat,
                    NbtDouble,
                    NbtByteArray,
                    NbtString,
                    NbtList,
                    NbtCompound,
                    NbtIntArray,
                    NbtLongArray {
        byte type();
    }

    record NbtByte(byte value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_BYTE;
        }
    }

    record NbtShort(short value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_SHORT;
        }
    }

    record NbtInt(int value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_INT;
        }
    }

    record NbtLong(long value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_LONG;
        }
    }

    record NbtFloat(float value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_FLOAT;
        }
    }

    record NbtDouble(double value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_DOUBLE;
        }
    }

    record NbtByteArray(byte[] value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_BYTE_ARRAY;
        }
    }

    record NbtString(String value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_STRING;
        }
    }

    record NbtIntArray(int[] value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_INT_ARRAY;
        }
    }

    record NbtLongArray(long[] value) implements NbtTag {
        @Override
        public byte type() {
            return TAG_LONG_ARRAY;
        }
    }

    /**
     * Mutable list tag. NBT lists are homogenous — {@link #elementType}
     * declares the element tag id and every element must be that type.
     * {@code TAG_END} (0) is the empty-list marker.
     */
    static final class NbtList implements NbtTag {
        byte elementType;
        final List<NbtTag> elements;

        NbtList(byte elementType, List<NbtTag> elements) {
            this.elementType = elementType;
            this.elements = elements;
        }

        NbtList() {
            this(TAG_END, new ArrayList<>());
        }

        @Override
        public byte type() {
            return TAG_LIST;
        }
    }

    /**
     * Mutable compound tag — key insertion order is preserved on read
     * for debuggability but the canonical writer sorts keys ascending
     * before emitting.
     */
    static final class NbtCompound implements NbtTag {
        final LinkedHashMap<String, NbtTag> entries;

        NbtCompound() {
            this.entries = new LinkedHashMap<>();
        }

        NbtCompound(LinkedHashMap<String, NbtTag> entries) {
            this.entries = entries;
        }

        @Override
        public byte type() {
            return TAG_COMPOUND;
        }

        NbtCompound put(String key, NbtTag tag) {
            entries.put(key, tag);
            return this;
        }
    }

    /**
     * Parse the NBT root compound from an uncompressed payload. Vanilla
     * always writes chunk NBT with a named-root compound, so the root
     * tag id is TAG_COMPOUND and the root name is usually empty.
     */
    static NbtCompound readNbtRoot(byte[] uncompressed) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(uncompressed))) {
            byte rootType = in.readByte();
            if (rootType != TAG_COMPOUND) {
                return null; // malformed NBT — caller falls back to raw hashing
            }
            in.readUTF(); // discard root name
            return readCompoundBody(in);
        }
    }

    private static NbtCompound readCompoundBody(DataInput in) throws IOException {
        NbtCompound c = new NbtCompound();
        while (true) {
            byte tagId = in.readByte();
            if (tagId == TAG_END) return c;
            String name = in.readUTF();
            c.entries.put(name, readPayload(in, tagId));
        }
    }

    private static NbtTag readPayload(DataInput in, byte tagId) throws IOException {
        return switch (tagId) {
            case TAG_BYTE -> new NbtByte(in.readByte());
            case TAG_SHORT -> new NbtShort(in.readShort());
            case TAG_INT -> new NbtInt(in.readInt());
            case TAG_LONG -> new NbtLong(in.readLong());
            case TAG_FLOAT -> new NbtFloat(in.readFloat());
            case TAG_DOUBLE -> new NbtDouble(in.readDouble());
            case TAG_BYTE_ARRAY -> {
                int len = in.readInt();
                if (len < 0) throw new IOException("negative byte-array length " + len);
                byte[] bs = new byte[len];
                in.readFully(bs);
                yield new NbtByteArray(bs);
            }
            case TAG_STRING -> new NbtString(in.readUTF());
            case TAG_LIST -> {
                byte elementType = in.readByte();
                int len = in.readInt();
                if (len < 0) throw new IOException("negative list length " + len);
                List<NbtTag> els = new ArrayList<>(Math.min(len, 1024));
                for (int i = 0; i < len; i++) {
                    els.add(readPayload(in, elementType));
                }
                yield new NbtList(elementType, els);
            }
            case TAG_COMPOUND -> readCompoundBody(in);
            case TAG_INT_ARRAY -> {
                int len = in.readInt();
                if (len < 0) throw new IOException("negative int-array length " + len);
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                yield new NbtIntArray(arr);
            }
            case TAG_LONG_ARRAY -> {
                int len = in.readInt();
                if (len < 0) throw new IOException("negative long-array length " + len);
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                yield new NbtLongArray(arr);
            }
            default -> throw new IOException("unknown NBT tag id " + tagId);
        };
    }

    /**
     * Canonicalise + serialise a root compound. Compound keys are
     * emitted in ascending {@link String#compareTo} order — this is what
     * makes byte-level SHA-256 stable across the LinkedHashMap
     * insertion-order differences that arise when Vanilla writes the
     * same logical chunk from different worker threads.
     */
    static byte[] writeCanonicalRoot(NbtCompound root) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF(""); // canonical empty root name
            writeCompoundBody(out, root);
        }
        return baos.toByteArray();
    }

    private static void writeCompoundBody(DataOutput out, NbtCompound c) throws IOException {
        // Sort keys ascending for canonical byte order.
        String[] keys = c.entries.keySet().toArray(new String[0]);
        Arrays.sort(keys);
        for (String key : keys) {
            NbtTag tag = c.entries.get(key);
            out.writeByte(tag.type());
            out.writeUTF(key);
            writePayload(out, tag);
        }
        out.writeByte(TAG_END);
    }

    private static void writePayload(DataOutput out, NbtTag tag) throws IOException {
        switch (tag) {
            case NbtByte t -> out.writeByte(t.value());
            case NbtShort t -> out.writeShort(t.value());
            case NbtInt t -> out.writeInt(t.value());
            case NbtLong t -> out.writeLong(t.value());
            case NbtFloat t -> out.writeFloat(t.value());
            case NbtDouble t -> out.writeDouble(t.value());
            case NbtByteArray t -> {
                out.writeInt(t.value().length);
                out.write(t.value());
            }
            case NbtString t -> out.writeUTF(t.value());
            case NbtList t -> {
                // Empty list still writes TAG_END as its element type per NBT spec.
                byte elemType = t.elements.isEmpty() ? TAG_END : t.elementType;
                out.writeByte(elemType);
                out.writeInt(t.elements.size());
                for (NbtTag el : t.elements) {
                    writePayload(out, el);
                }
            }
            case NbtCompound t -> writeCompoundBody(out, t);
            case NbtIntArray t -> {
                out.writeInt(t.value().length);
                for (int v : t.value()) out.writeInt(v);
            }
            case NbtLongArray t -> {
                out.writeInt(t.value().length);
                for (long v : t.value()) out.writeLong(v);
            }
        }
    }

    /**
     * Apply the M8 semantic normalisation rules to a chunk NBT root in
     * place. Idempotent — running twice on the same tree yields the
     * same result as running once.
     *
     * <p>Rules (per {@code docs/design/nbt-semantic-diff.md} sections
     * 3-5, task-scoped subset):
     * <ul>
     *   <li>Drop {@code random_state} at any depth (per-thread WorldGen
     *       RNG scratch — differs per worker, no gameplay impact).
     *   <li>Entity list ({@code entities} or {@code Entities}): drop
     *       volatile fields ({@code Air}, {@code HurtTime}, {@code
     *       DeathTime}, {@code PortalCooldown}, plus {@code Motion} when
     *       |v|^2 &lt; 1e-6 canonicalised to a zero vector); sort by UUID
     *       string ascending.
     *   <li>Block-entity list ({@code block_entities} or {@code
     *       BlockEntities}): sort by {@code (x, y, z)}.
     *   <li>Ticker lists ({@code block_ticks}, {@code fluid_ticks}):
     *       sort by {@code (x, y, z, t, p)}; duplicates by that tuple
     *       are preserved and their relative order collapses to a
     *       stable order (all identical anyway).
     * </ul>
     */
    static void normaliseCompound(NbtCompound c) {
        c.entries.remove("random_state");
        for (Map.Entry<String, NbtTag> entry : c.entries.entrySet()) {
            String key = entry.getKey();
            NbtTag value = entry.getValue();
            if (value instanceof NbtList list) {
                if ("entities".equals(key) || "Entities".equals(key)) {
                    normaliseEntityList(list);
                } else if ("block_entities".equals(key) || "BlockEntities".equals(key)) {
                    sortListByBlockPos(list);
                } else if ("block_ticks".equals(key) || "fluid_ticks".equals(key)) {
                    sortListByTickerKey(list);
                }
                for (NbtTag el : list.elements) {
                    if (el instanceof NbtCompound sub) normaliseCompound(sub);
                }
            } else if (value instanceof NbtCompound sub) {
                normaliseCompound(sub);
            }
        }
    }

    private static void normaliseEntityList(NbtList list) {
        for (NbtTag el : list.elements) {
            if (!(el instanceof NbtCompound e)) continue;
            e.entries.remove("Air");
            e.entries.remove("HurtTime");
            e.entries.remove("DeathTime");
            e.entries.remove("PortalCooldown");
            canonicaliseIdleMotion(e);
        }
        list.elements.sort(Comparator.comparing(WorldDiff::entityUuidKey));
    }

    /**
     * Idle-drift canonicalisation for the entity {@code Motion} vector.
     * When |v|^2 &lt; 1e-6 the entity is at rest to within float noise;
     * per the design doc that noise is not a gameplay-observable
     * difference. Rewriting to a canonical zero vector on both sides is
     * safer than removing the field asymmetrically — two runs that
     * differ only in idle drift both normalise to {@code [0, 0, 0]}
     * and hash equal, while a "moving vs. idle" pair keeps its
     * genuine difference (one has zero vector, the other keeps its
     * real motion).
     */
    private static void canonicaliseIdleMotion(NbtCompound entity) {
        NbtTag motion = entity.entries.get("Motion");
        if (!(motion instanceof NbtList ml) || ml.elements.size() != 3) return;
        if (ml.elementType != TAG_DOUBLE) return;
        double x = ((NbtDouble) ml.elements.get(0)).value();
        double y = ((NbtDouble) ml.elements.get(1)).value();
        double z = ((NbtDouble) ml.elements.get(2)).value();
        if (x * x + y * y + z * z < 1e-6) {
            List<NbtTag> zeroed = new ArrayList<>(3);
            zeroed.add(new NbtDouble(0.0));
            zeroed.add(new NbtDouble(0.0));
            zeroed.add(new NbtDouble(0.0));
            entity.entries.put("Motion", new NbtList(TAG_DOUBLE, zeroed));
        }
    }

    /**
     * Sort key for entities: modern UUID (4-int {@code IntArrayTag}) or
     * legacy {@code UUIDMost}/{@code UUIDLeast} long pair, composed
     * into a {@link UUID} and stringified for lexicographic sort. An
     * entity missing both encodings sorts as an empty string, which
     * keeps a UUID-less fixture deterministic.
     */
    private static String entityUuidKey(NbtTag el) {
        if (!(el instanceof NbtCompound e)) return "";
        NbtTag uuid = e.entries.get("UUID");
        if (uuid instanceof NbtIntArray ia && ia.value().length == 4) {
            int[] v = ia.value();
            long msb = (((long) v[0]) << 32) | (v[1] & 0xFFFFFFFFL);
            long lsb = (((long) v[2]) << 32) | (v[3] & 0xFFFFFFFFL);
            return new UUID(msb, lsb).toString();
        }
        NbtTag most = e.entries.get("UUIDMost");
        NbtTag least = e.entries.get("UUIDLeast");
        if (most instanceof NbtLong m && least instanceof NbtLong l) {
            return new UUID(m.value(), l.value()).toString();
        }
        return "";
    }

    /** Sort a block-entity list by {@code (x, y, z)} ints from the compound. */
    private static void sortListByBlockPos(NbtList list) {
        list.elements.sort(Comparator.comparingInt((NbtTag el) -> intField(el, "x"))
                .thenComparingInt(el -> intField(el, "y"))
                .thenComparingInt(el -> intField(el, "z")));
    }

    /**
     * Sort a ticker list by {@code (x, y, z, delay, priority)}. Uses
     * the Vanilla short field names — {@code t} = delay,
     * {@code p} = priority.
     */
    private static void sortListByTickerKey(NbtList list) {
        list.elements.sort(Comparator.comparingInt((NbtTag el) -> intField(el, "x"))
                .thenComparingInt(el -> intField(el, "y"))
                .thenComparingInt(el -> intField(el, "z"))
                .thenComparingInt(el -> intField(el, "t"))
                .thenComparingInt(el -> intField(el, "p")));
    }

    /** Read an {@code IntTag} field from a compound, defaulting to 0 for missing/wrong-type. */
    private static int intField(NbtTag el, String key) {
        if (!(el instanceof NbtCompound c)) return 0;
        NbtTag v = c.entries.get(key);
        return v instanceof NbtInt i ? i.value() : 0;
    }

    private static final byte[] SLOT_EMPTY_SENTINEL = {(byte) 0xE0, (byte) 0x5E, 0, 0}; // "àSE  "

    private static final byte[] SLOT_MALFORMED_SENTINEL = {(byte) 0xBA, (byte) 0xDF, (byte) 0x00, (byte) 0x0D};

    private static byte[] slotIndexBytes(int slot) {
        return new byte[] {(byte) (slot >>> 24), (byte) (slot >>> 16), (byte) (slot >>> 8), (byte) slot};
    }

    private static void hashMalformedSlot(MessageDigest md, int slot, int loc) {
        md.update(SLOT_MALFORMED_SENTINEL);
        md.update(slotIndexBytes(slot));
        // Include the raw loc entry so different corrupt values distinguish from each other.
        md.update(new byte[] {(byte) (loc >>> 24), (byte) (loc >>> 16), (byte) (loc >>> 8), (byte) loc});
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * Result of comparing two world directories.
     *
     * @param onlyInBaseline files present in the baseline but not in the patched world
     * @param onlyInPatched files present in the patched world but not in the baseline
     * @param mismatched files present in both but whose contents differ (rel path → baseline hash / patched hash)
     */
    public record Result(
            Path baselineRoot,
            Path patchedRoot,
            java.util.NavigableMap<String, String> baselineHashes,
            java.util.NavigableMap<String, String> patchedHashes) {

        public boolean matches() {
            return onlyInBaseline().isEmpty()
                    && onlyInPatched().isEmpty()
                    && mismatched().isEmpty();
        }

        public Set<String> onlyInBaseline() {
            TreeMap<String, String> a = new TreeMap<>(baselineHashes);
            a.keySet().removeAll(patchedHashes.keySet());
            return a.keySet();
        }

        public Set<String> onlyInPatched() {
            TreeMap<String, String> a = new TreeMap<>(patchedHashes);
            a.keySet().removeAll(baselineHashes.keySet());
            return a.keySet();
        }

        public java.util.NavigableMap<String, String[]> mismatched() {
            TreeMap<String, String[]> out = new TreeMap<>();
            for (var e : baselineHashes.entrySet()) {
                String rel = e.getKey();
                String bh = e.getValue();
                String ph = patchedHashes.get(rel);
                if (ph != null && !ph.equals(bh)) {
                    out.put(rel, new String[] {bh, ph});
                }
            }
            return out;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("DeterminismHarness: comparing ")
                    .append(baselineRoot)
                    .append(" ↔ ")
                    .append(patchedRoot)
                    .append('\n');
            sb.append("  baseline files: ").append(baselineHashes.size()).append('\n');
            sb.append("  patched  files: ").append(patchedHashes.size()).append('\n');
            if (matches()) {
                sb.append("  RESULT: MATCH (byte-identical, ignoring known-nondeterministic paths)\n");
            } else {
                sb.append("  RESULT: MISMATCH\n");
                Set<String> only1 = onlyInBaseline();
                if (!only1.isEmpty()) {
                    sb.append("    only in baseline (").append(only1.size()).append("):\n");
                    only1.stream()
                            .limit(20)
                            .forEach(p -> sb.append("      ").append(p).append('\n'));
                    if (only1.size() > 20)
                        sb.append("      ... (").append(only1.size() - 20).append(" more)\n");
                }
                Set<String> only2 = onlyInPatched();
                if (!only2.isEmpty()) {
                    sb.append("    only in patched (").append(only2.size()).append("):\n");
                    only2.stream()
                            .limit(20)
                            .forEach(p -> sb.append("      ").append(p).append('\n'));
                    if (only2.size() > 20)
                        sb.append("      ... (").append(only2.size() - 20).append(" more)\n");
                }
                var mm = mismatched();
                if (!mm.isEmpty()) {
                    sb.append("    content differs (").append(mm.size()).append("):\n");
                    mm.entrySet().stream().limit(20).forEach(e -> sb.append("      ")
                            .append(e.getKey())
                            .append("  baseline=")
                            .append(e.getValue()[0], 0, 12)
                            .append("  patched=")
                            .append(e.getValue()[1], 0, 12)
                            .append('\n'));
                    if (mm.size() > 20)
                        sb.append("      ... (").append(mm.size() - 20).append(" more)\n");
                }
            }
            return sb.toString();
        }
    }
}
