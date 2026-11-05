package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisKeyBuilderTest {

    private final RedisKeyBuilder builder = new RedisKeyBuilder("jync:12:");

    @Test
    @DisplayName("a single-column primary key becomes prefix:table:value")
    void singleKey() {
        assertThat(builder.key("orders", List.of("id"), row("id", 1001)))
                .isEqualTo("jync:12:orders:1001");
    }

    @Test
    @DisplayName("composite keys keep the declared column order")
    void compositeKey() {
        assertThat(builder.key("order_items", List.of("order_id", "line_no"),
                row("line_no", 2, "order_id", 1001)))
                .isEqualTo("jync:12:order_items:1001:2");
    }

    @Test
    @DisplayName("a prefix without a trailing colon gets one")
    void prefixIsPrepended() {
        assertThat(new RedisKeyBuilder("cache").key("orders", List.of("id"), row("id", 1)))
                .isEqualTo("cache:orders:1");
    }

    @Test
    @DisplayName("a ':' or '%' inside a key component is percent-escaped")
    void colonAndPercentInPkAreEscaped() {
        assertThat(builder.key("orders", List.of("id"), row("id", "a:b%c")))
                .isEqualTo("jync:12:orders:a%3Ab%25c");
    }

    @Test
    @DisplayName("escaping keeps two different values from sharing a key")
    void twoDistinctValuesDoNotCollide() {
        String raw = builder.key("orders", List.of("id"), row("id", "a:b"));
        String escaped = builder.key("orders", List.of("id"), row("id", "a%3Ab"));

        assertThat(raw).isNotEqualTo(escaped);
    }

    @Test
    @DisplayName("a binary key component is written as hex")
    void binaryPkUsesHex() {
        assertThat(builder.key("blobs", List.of("id"), row("id", new byte[] {0x0a, (byte) 0xff})))
                .isEqualTo("jync:12:blobs:0aff");
    }

    @Test
    @DisplayName("a null key component is rejected")
    void nullPkComponentIsRejected() {
        assertThatThrownBy(() -> builder.key("orders", List.of("id"), row("id", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }
}
