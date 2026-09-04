/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 8949 CBOR codec covering just the subset MultiForge license
 * payloads use: unsigned integer, text string, array, map, null. Not a
 * general CBOR library.
 */
public final class Cbor {

    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "cbor:null";
        }
    };

    private Cbor() {}

    // ----- decoder --------------------------------------------------------

    public static Object decode(byte[] bytes) {
        Decoder d = new Decoder(bytes);
        Object value = d.readValue();
        if (d.pos != bytes.length) {
            throw new IllegalArgumentException("Trailing bytes after CBOR value at offset " + d.pos);
        }
        return value;
    }

    private static final class Decoder {
        final byte[] buf;
        int pos;

        Decoder(byte[] buf) {
            this.buf = buf;
        }

        Object readValue() {
            int ib = readByte() & 0xFF;
            int major = ib >>> 5;
            int info = ib & 0x1F;
            return switch (major) {
                case 0 -> readLength(info); // unsigned int → Long
                case 1 -> -1L - readLength(info); // negative int
                case 3 -> readText(info);
                case 4 -> readArray(info);
                case 5 -> readMap(info);
                case 7 -> readSimple(info);
                default -> throw new IllegalArgumentException("Unsupported CBOR major type " + major);
            };
        }

        long readLength(int info) {
            if (info < 24) return info;
            return switch (info) {
                case 24 -> readByte() & 0xFFL;
                case 25 -> ((readByte() & 0xFFL) << 8) | (readByte() & 0xFFL);
                case 26 -> ((readByte() & 0xFFL) << 24)
                        | ((readByte() & 0xFFL) << 16)
                        | ((readByte() & 0xFFL) << 8)
                        | (readByte() & 0xFFL);
                case 27 -> {
                    long v = 0;
                    for (int i = 0; i < 8; i++) v = (v << 8) | (readByte() & 0xFFL);
                    if (v < 0) throw new IllegalArgumentException("uint64 exceeds signed long range");
                    yield v;
                }
                default -> throw new IllegalArgumentException("Unsupported CBOR length info " + info);
            };
        }

        String readText(int info) {
            int len = Math.toIntExact(readLength(info));
            String s = new String(buf, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        List<Object> readArray(int info) {
            int len = Math.toIntExact(readLength(info));
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) out.add(readValue());
            return out;
        }

        Map<Object, Object> readMap(int info) {
            int len = Math.toIntExact(readLength(info));
            Map<Object, Object> out = new LinkedHashMap<>(len * 2);
            for (int i = 0; i < len; i++) {
                Object k = readValue();
                Object v = readValue();
                out.put(k, v);
            }
            return out;
        }

        Object readSimple(int info) {
            return switch (info) {
                case 20 -> Boolean.FALSE;
                case 21 -> Boolean.TRUE;
                case 22 -> NULL;
                case 23 -> NULL;
                default -> throw new IllegalArgumentException("Unsupported CBOR simple " + info);
            };
        }

        byte readByte() {
            if (pos >= buf.length) throw new IllegalArgumentException("Unexpected end of CBOR at offset " + pos);
            return buf[pos++];
        }
    }

    // ----- encoder --------------------------------------------------------

    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeValue(out, value);
        return out.toByteArray();
    }

    private static void writeValue(ByteArrayOutputStream out, Object v) {
        if (v == null || v == NULL) {
            out.write((7 << 5) | 22);
        } else if (v instanceof Boolean b) {
            out.write((7 << 5) | (b ? 21 : 20));
        } else if (v instanceof Long l) {
            writeInteger(out, l);
        } else if (v instanceof Integer i) {
            writeInteger(out, i.longValue());
        } else if (v instanceof String s) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            writeHead(out, 3, utf8.length);
            out.write(utf8, 0, utf8.length);
        } else if (v instanceof List<?> list) {
            writeHead(out, 4, list.size());
            for (Object e : list) writeValue(out, e);
        } else if (v instanceof Map<?, ?> map) {
            writeHead(out, 5, map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                writeValue(out, e.getKey());
                writeValue(out, e.getValue());
            }
        } else {
            throw new IllegalArgumentException("Unsupported CBOR value: " + v.getClass());
        }
    }

    private static void writeInteger(ByteArrayOutputStream out, long v) {
        if (v >= 0) {
            writeHead(out, 0, v);
        } else {
            writeHead(out, 1, -1L - v);
        }
    }

    private static void writeHead(ByteArrayOutputStream out, int major, long len) {
        int prefix = major << 5;
        if (len < 24) {
            out.write(prefix | (int) len);
        } else if (len < 0x100L) {
            out.write(prefix | 24);
            out.write((int) len);
        } else if (len < 0x10000L) {
            out.write(prefix | 25);
            out.write((int) (len >>> 8));
            out.write((int) len);
        } else if (len < 0x100000000L) {
            out.write(prefix | 26);
            out.write((int) (len >>> 24));
            out.write((int) (len >>> 16));
            out.write((int) (len >>> 8));
            out.write((int) len);
        } else {
            out.write(prefix | 27);
            for (int i = 7; i >= 0; i--) out.write((int) (len >>> (i * 8)));
        }
    }
}
