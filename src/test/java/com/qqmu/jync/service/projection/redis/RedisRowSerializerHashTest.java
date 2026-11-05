package com.qqmu.jync.service.projection.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisRowSerializerHashTest {

    private final RedisRowSerializer serializer =
            new RedisRowSerializerHash(Set.of(), 1_048_576, 0);

    @Test
    @DisplayName("every non-null column becomes a field with its text representation")
    void fieldsUseColumnNamesAndStringValues() {
        RedisMutation mutation = serializer.upsert("k", row(
                "id", 1001L,
                "amount", new BigDecimal("128.50"),
                "paid", true,
                "created_at", LocalDateTime.of(1948, 5, 1, 0, 0, 0)));

        assertThat(mutation.kind()).isEqualTo(RedisMutation.Kind.HSET);
        assertThat(mutation.fields()).containsExactly(
                Map.entry("id", "1001"),
                Map.entry("amount", "128.50"),
                Map.entry("paid", "true"),
                Map.entry("created_at", "1948-05-01T00:00:00"));
        assertThat(mutation.deleteFields()).isEmpty();
    }

    @Test
    @DisplayName("a SQL NULL becomes an HDEL so a stale field disappears")
    void nullFieldsBecomeDeleteFields() {
        RedisMutation mutation = serializer.upsert("k", row("id", 1, "memo", null));

        assertThat(mutation.fields()).containsOnly(Map.entry("id", "1"));
        assertThat(mutation.deleteFields()).containsExactly("memo");
    }

    @Test
    @DisplayName("excluded columns are omitted case-insensitively")
    void excludedColumnsAreOmitted() {
        RedisRowSerializer filtering =
                new RedisRowSerializerHash(Set.of("secret_pin"), 1_048_576, 0);

        RedisMutation mutation = filtering.upsert("k", row("id", 1, "SECRET_PIN", "1234"));

        assertThat(mutation.fields()).containsOnly(Map.entry("id", "1"));
        assertThat(mutation.deleteFields()).isEmpty();
    }

    @Test
    @DisplayName("a binary value is written as Base64")
    void binaryIsBase64() {
        RedisMutation mutation = serializer.upsert("k", row("blob", new byte[] {1, 2, 3}));

        assertThat(mutation.fields()).containsOnly(Map.entry("blob", "AQID"));
    }

    @Test
    @DisplayName("delete emits DEL")
    void deleteEmitsDel() {
        RedisMutation mutation = serializer.delete("k");

        assertThat(mutation.kind()).isEqualTo(RedisMutation.Kind.DEL);
        assertThat(mutation.key()).isEqualTo("k");
        assertThat(mutation.fields()).isEmpty();
    }

    @Test
    @DisplayName("the configured TTL rides along with the mutation")
    void ttlIsCarried() {
        RedisRowSerializer expiring = new RedisRowSerializerHash(Set.of(), 1_048_576, 30);

        assertThat(expiring.upsert("k", row("id", 1)).ttlSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("a row whose fields exceed the byte budget is skipped, not thrown")
    void oversizedRowIsSkipped() {
        RedisRowSerializer small = new RedisRowSerializerHash(Set.of(), 10, 0);

        assertThat(small.upsert("k", row("memo", "abcdefghij"))).isNull();
    }

    @Test
    @DisplayName("an unsupported value type skips the row instead of guessing")
    void unknownTypeIsSkipped() {
        assertThat(serializer.upsert("k", row("weird", new Object()))).isNull();
    }

    @Test
    @DisplayName("empty delete-field lists are never emitted as HDEL")
    void nullOnlyRowHasNoHdel() {
        assertThat(serializer.delete("k").deleteFields()).isEmpty();
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }
}
