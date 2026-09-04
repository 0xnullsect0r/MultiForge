/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.license.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CborTest {

    @Test
    void encodesAndDecodesSmallInteger() {
        byte[] bytes = Cbor.encode(23L);
        assertThat(bytes).containsExactly(0x17);
        assertThat(Cbor.decode(bytes)).isEqualTo(23L);
    }

    @Test
    void encodesAndDecodesUint8() {
        assertThat(Cbor.decode(Cbor.encode(200L))).isEqualTo(200L);
    }

    @Test
    void encodesAndDecodesUint16() {
        assertThat(Cbor.decode(Cbor.encode(65535L))).isEqualTo(65535L);
    }

    @Test
    void encodesAndDecodesUint32() {
        assertThat(Cbor.decode(Cbor.encode(4_000_000_000L))).isEqualTo(4_000_000_000L);
    }

    @Test
    void encodesAndDecodesText() {
        assertThat(Cbor.decode(Cbor.encode("multiforge"))).isEqualTo("multiforge");
    }

    @Test
    void encodesAndDecodesNull() {
        assertThat(Cbor.decode(Cbor.encode(Cbor.NULL))).isSameAs(Cbor.NULL);
    }

    @Test
    void encodesAndDecodesMap() {
        Map<Object, Object> in = new LinkedHashMap<>();
        in.put("v", 1L);
        in.put("features", List.of("core"));
        in.put("exp", Cbor.NULL);

        Map<?, ?> out = (Map<?, ?>) Cbor.decode(Cbor.encode(in));
        assertThat(out.get("v")).isEqualTo(1L);
        assertThat(out.get("features")).isEqualTo(List.of("core"));
        assertThat(out.get("exp")).isSameAs(Cbor.NULL);
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] good = Cbor.encode("x");
        byte[] bad = new byte[good.length + 1];
        System.arraycopy(good, 0, bad, 0, good.length);
        assertThatThrownBy(() -> Cbor.decode(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
