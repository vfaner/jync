package com.qqmu.jync.service.etl.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.time.Instant;
import java.util.Date;

import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

/**
 * A checkpoint stores the resume-key value as text because the checkpoint table is shared
 * with the JDBC sources. The codec has to round-trip every BSON key type the schema
 * inference can produce, and refuse the ones a {@code $gt} comparison cannot reproduce.
 */
class MongoResumeKeyCodecTest {

    @Test
    void nullStaysNull() {
        assertThat(MongoResumeKeyCodec.encode(null)).isNull();
        assertThat(MongoResumeKeyCodec.decode(null, Types.VARCHAR)).isNull();
    }

    @Test
    void objectIdRoundTripsThroughHexText() {
        ObjectId id = new ObjectId("507f1f77bcf86cd799439011");

        String encoded = MongoResumeKeyCodec.encode(id);

        assertThat(encoded).isEqualTo("507f1f77bcf86cd799439011");
        assertThat(MongoResumeKeyCodec.decode(encoded, Types.VARCHAR)).isEqualTo(id);
    }

    @Test
    void stringsRoundTripAsClob() {
        String text = "order-中文";

        String encoded = MongoResumeKeyCodec.encode(text);

        assertThat(encoded).isEqualTo(text);
        assertThat(MongoResumeKeyCodec.decode(encoded, Types.CLOB)).isEqualTo(text);
    }

    @Test
    void numericTypesRoundTrip() {
        assertThat(MongoResumeKeyCodec.decode(
                MongoResumeKeyCodec.encode(7), Types.INTEGER)).isEqualTo(7);
        assertThat(MongoResumeKeyCodec.decode(
                MongoResumeKeyCodec.encode(7L), Types.BIGINT)).isEqualTo(7L);
        assertThat(MongoResumeKeyCodec.decode(
                MongoResumeKeyCodec.encode(1.5d), Types.DOUBLE)).isEqualTo(1.5d);
    }

    @Test
    void decimal128RoundTripsAsDecimal() {
        Decimal128 value = Decimal128.parse("123.4500");

        String encoded = MongoResumeKeyCodec.encode(value);

        assertThat(encoded).isEqualTo("123.4500");
        assertThat(MongoResumeKeyCodec.decode(encoded, Types.DECIMAL)).isEqualTo(value);
    }

    @Test
    void datesRoundTripThroughIsoInstantAtMillisecondPrecision() {
        Date value = Date.from(Instant.parse("2026-10-31T08:30:15.123Z"));

        String encoded = MongoResumeKeyCodec.encode(value);

        assertThat(encoded).isEqualTo("2026-10-31T08:30:15.123Z");
        assertThat(MongoResumeKeyCodec.decode(encoded, Types.TIMESTAMP)).isEqualTo(value);
    }

    @Test
    void booleansRoundTripAsSmallInt() {
        assertThat(MongoResumeKeyCodec.encode(Boolean.TRUE)).isEqualTo("true");
        assertThat(MongoResumeKeyCodec.decode("true", Types.SMALLINT)).isEqualTo(Boolean.TRUE);
        assertThat(MongoResumeKeyCodec.encode(Boolean.FALSE)).isEqualTo("false");
        assertThat(MongoResumeKeyCodec.decode("false", Types.SMALLINT)).isEqualTo(Boolean.FALSE);
    }

    @Test
    void aBsonTimestampKeyIsRefusedBecauseLongCannotCompare() {
        assertThatThrownBy(() -> MongoResumeKeyCodec.encode(new BsonTimestamp(1700000000, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.resume.type");
    }

    @Test
    void aDocumentValueCannotBeAResumeKey() {
        assertThatThrownBy(() -> MongoResumeKeyCodec.encode(new Document("a", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.resume.type");
    }

    @Test
    void anOverlongEncodedKeyIsRefused() {
        assertThatThrownBy(() -> MongoResumeKeyCodec.encode("x".repeat(1025)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.resume.tooLong");
    }

    @Test
    void aMalformedStoredValueIsRefused() {
        assertThatThrownBy(() -> MongoResumeKeyCodec.decode("not-an-object-id", Types.VARCHAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.resume.invalid");

        assertThatThrownBy(() -> MongoResumeKeyCodec.decode("not-a-decimal", Types.DECIMAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.resume.invalid");

        assertThatThrownBy(() -> MongoResumeKeyCodec.decode("not-a-date", Types.TIMESTAMP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.resume.invalid");
    }

    @Test
    void anUnknownJdbcTypeIsRefused() {
        assertThatThrownBy(() -> MongoResumeKeyCodec.decode("x", Types.ARRAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.resume.invalid");
    }

    @Test
    void integerSmallIntAndBooleanDecodingAcceptTheirStoredForms() {
        assertThat(MongoResumeKeyCodec.decode("1", Types.SMALLINT)).isEqualTo(Boolean.TRUE);
        assertThat(MongoResumeKeyCodec.decode("0", Types.SMALLINT)).isEqualTo(Boolean.FALSE);

        assertThatThrownBy(() -> MongoResumeKeyCodec.decode("2", Types.SMALLINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.mongo.resume.invalid");
    }
}
