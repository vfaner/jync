package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisRowSerializerJsonTest {

    private final RedisRowSerializer serializer =
            new RedisRowSerializerJson(Set.of(), 1_048_576, 0);

    @Test
    @DisplayName("integer columns are JSON numbers")
    void integersAreJsonNumbers() {
        RedisMutation mutation = serializer.upsert("k", row("id", 1001L, "small", (short) 7));

        assertThat(mutation.kind()).isEqualTo(RedisMutation.Kind.SET);
        assertThat(mutation.key()).isEqualTo("k");
        assertThat(mutation.jsonValue()).isEqualTo("{\"id\":1001,\"small\":7}");
    }

    @Test
    @DisplayName("DECIMAL is a JSON string so consumers cannot lose precision")
    void decimalsAreJsonStrings() {
        RedisMutation mutation = serializer.upsert("k", row("amount", new BigDecimal("128.50")));

        assertThat(mutation.jsonValue()).isEqualTo("{\"amount\":\"128.50\"}");
    }

    @Test
    @DisplayName("floating point columns are JSON numbers")
    void floatsAreJsonNumbers() {
        RedisMutation mutation = serializer.upsert("k", row("ratio", 0.25d, "rate", 1.5f));

        assertThat(mutation.jsonValue()).isEqualTo("{\"ratio\":0.25,\"rate\":1.5}");
    }

    @Test
    @DisplayName("booleans are JSON booleans")
    void booleansAreJsonBooleans() {
        RedisMutation mutation = serializer.upsert("k", row("paid", true, "void", false));

        assertThat(mutation.jsonValue()).isEqualTo("{\"paid\":true,\"void\":false}");
    }

    @Test
    @DisplayName("a timestamp without zone is written as wall-clock ISO-8601")
    void localTimestampIsWallClockIso() {
        RedisMutation mutation = serializer.upsert("k",
                row("created_at", LocalDateTime.of(1948, 5, 1, 0, 0, 0)));

        assertThat(mutation.jsonValue()).isEqualTo("{\"created_at\":\"1948-05-01T00:00:00\"}");
    }

    @Test
    @DisplayName("a timestamp with an offset keeps the offset")
    void offsetTimestampKeepsOffset() {
        RedisMutation mutation = serializer.upsert("k",
                row("created_at", OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0,
                        ZoneOffset.ofHours(8))));

        assertThat(mutation.jsonValue()).isEqualTo("{\"created_at\":\"2024-01-02T03:04:05+08:00\"}");
    }

    @Test
    @DisplayName("text is escaped and SQL NULL is JSON null")
    void textAndNull() {
        RedisMutation mutation = serializer.upsert("k", row("memo", "a\"b\\c\nd", "note", null));

        assertThat(mutation.jsonValue()).isEqualTo("{\"memo\":\"a\\\"b\\\\c\\nd\",\"note\":null}");
    }

    @Test
    @DisplayName("a binary column is written as Base64")
    void binaryIsBase64() {
        RedisMutation mutation = serializer.upsert("k", row("blob", new byte[] {1, 2, 3}));

        assertThat(mutation.jsonValue()).isEqualTo("{\"blob\":\"AQID\"}");
    }

    @Test
    @DisplayName("excluded columns are omitted case-insensitively")
    void excludedColumnsAreOmitted() {
        RedisRowSerializer filtering =
                new RedisRowSerializerJson(Set.of("secret_pin"), 1_048_576, 0);

        RedisMutation mutation = filtering.upsert("k", row("id", 1, "SECRET_PIN", "1234"));

        assertThat(mutation.jsonValue()).isEqualTo("{\"id\":1}");
    }

    @Test
    @DisplayName("a row whose encoding exceeds the byte budget is skipped, not thrown")
    void oversizedRowIsSkipped() {
        RedisRowSerializer small = new RedisRowSerializerJson(Set.of(), 10, 0);

        assertThat(small.upsert("k", row("memo", "abcdefghij"))).isNull();
    }

    @Test
    @DisplayName("an unsupported value type skips the row instead of guessing")
    void unknownTypeIsSkipped() {
        assertThat(serializer.upsert("k", row("weird", new Object()))).isNull();
    }

    @Test
    @DisplayName("delete emits DEL and carries no value")
    void deleteEmitsDel() {
        RedisMutation mutation = serializer.delete("k");

        assertThat(mutation.kind()).isEqualTo(RedisMutation.Kind.DEL);
        assertThat(mutation.key()).isEqualTo("k");
        assertThat(mutation.jsonValue()).isNull();
    }

    @Test
    @DisplayName("the configured TTL rides along with the mutation")
    void ttlIsCarried() {
        RedisRowSerializer expiring = new RedisRowSerializerJson(Set.of(), 1_048_576, 30);

        assertThat(expiring.upsert("k", row("id", 1)).ttlSeconds()).isEqualTo(30);
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }
}
