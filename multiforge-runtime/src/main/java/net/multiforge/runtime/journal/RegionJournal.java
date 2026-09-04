/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.journal;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.multiforge.runtime.region.RegionId;

/**
 * Append-only per-region write-ahead log. Every mutation that would
 * cross an autosave-budget boundary is journaled first; if the JVM
 * crashes, boot-time recovery replays committed entries and the
 * region continues from that state.
 *
 * <p>Layout (little-endian, per entry):
 * <pre>
 *   int32   magic     = 0xF01A4A4C  ("MJLC" if it were ASCII)
 *   int32   version   = 1
 *   int64   regionId
 *   int64   sequence
 *   int8    kindOrdinal
 *   int32   payloadLen
 *   byte[]  payload
 *   int32   crc32(regionId..payload)
 * </pre>
 *
 * <p>Not thread-safe: the owning region's worker is the single writer.
 * Readers may open the same file at boot time in read-only mode.
 */
public final class RegionJournal implements AutoCloseable {

    static final int MAGIC = 0xF01A4A4C;
    static final int VERSION = 1;

    private final RegionId region;
    private final Path file;
    private final RandomAccessFile raf;
    private final FileChannel channel;
    private final AtomicLong nextSequence = new AtomicLong();
    private volatile boolean closed;

    public RegionJournal(RegionId region, Path file) throws IOException {
        this.region = Objects.requireNonNull(region, "region");
        this.file = Objects.requireNonNull(file, "file");
        Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
        this.raf = new RandomAccessFile(file.toFile(), "rw");
        this.channel = raf.getChannel();
        this.raf.seek(raf.length());
        // Peek prior max sequence so restart continues numbering.
        // -1 sentinel so an empty journal starts numbering at 0.
        long max = -1L;
        try (var it = replay()) {
            while (it.hasNext()) {
                JournalEntry e = it.next();
                if (e.sequence() > max) max = e.sequence();
            }
        }
        nextSequence.set(max + 1);
        this.raf.seek(raf.length());
    }

    /** Append + fsync. Returns the assigned sequence. */
    public long append(JournalEntryKind kind, byte[] payload) throws IOException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payload, "payload");
        long seq = nextSequence.getAndIncrement();
        raf.writeInt(MAGIC);
        raf.writeInt(VERSION);
        raf.writeLong(region.value());
        raf.writeLong(seq);
        raf.writeByte(kind.ordinal());
        raf.writeInt(payload.length);
        raf.write(payload);
        raf.writeInt(crc(region.value(), seq, kind, payload));
        channel.force(true);
        return seq;
    }

    /** Iterator over every committed entry in the file, in order. */
    public ReplayIterator replay() throws IOException {
        return new ReplayIterator();
    }

    /** Snapshot of every committed entry — convenience for tests. */
    public List<JournalEntry> readAll() throws IOException {
        List<JournalEntry> out = new ArrayList<>();
        try (var it = replay()) {
            while (it.hasNext()) out.add(it.next());
        }
        return out;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        channel.force(true);
        raf.close();
    }

    public boolean isClosed() {
        return closed;
    }

    public Path path() {
        return file;
    }

    static int crc(long regionId, long seq, JournalEntryKind kind, byte[] payload) {
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update((int) (regionId >>> 32));
        c.update((int) regionId);
        c.update((int) (seq >>> 32));
        c.update((int) seq);
        c.update(kind.ordinal());
        c.update(payload);
        return (int) c.getValue();
    }

    /** Iterator over on-disk entries; caller must close. */
    public final class ReplayIterator implements AutoCloseable, java.util.Iterator<JournalEntry> {
        private final RandomAccessFile in;
        private JournalEntry next;

        ReplayIterator() throws IOException {
            this.in = new RandomAccessFile(file.toFile(), "r");
            this.in.seek(0);
            advance();
        }

        private void advance() throws IOException {
            long remaining = in.length() - in.getFilePointer();
            if (remaining < 4 + 4 + 8 + 8 + 1 + 4 + 4) {
                next = null;
                return;
            }
            try {
                int magic = in.readInt();
                if (magic != MAGIC)
                    throw new IOException("journal magic mismatch at offset " + (in.getFilePointer() - 4));
                int version = in.readInt();
                if (version != VERSION) throw new IOException("journal version " + version + " not supported");
                long regionId = in.readLong();
                long seq = in.readLong();
                int kindOrd = in.readByte();
                JournalEntryKind kind = JournalEntryKind.values()[kindOrd];
                int payloadLen = in.readInt();
                if (payloadLen < 0 || payloadLen > 128 * 1024 * 1024) {
                    throw new IOException("payload length " + payloadLen + " looks corrupt");
                }
                byte[] payload = new byte[payloadLen];
                in.readFully(payload);
                int crc = in.readInt();
                int expected = RegionJournal.crc(regionId, seq, kind, payload);
                if (crc != expected) throw new IOException("journal CRC mismatch at seq=" + seq);
                next = new JournalEntry(new RegionId(regionId), seq, kind, payload);
            } catch (EOFException e) {
                next = null;
            }
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public JournalEntry next() {
            JournalEntry n = next;
            if (n == null) throw new java.util.NoSuchElementException();
            try {
                advance();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** Open a journal file that need not exist yet — creates parent dirs. */
    public static RegionJournal open(RegionId region, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("region-" + region.value() + ".mjl");
        Files.write(file, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return new RegionJournal(region, file);
    }
}
