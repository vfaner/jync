package com.qqmu.jync.service.etl.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.etl.EtlSourceColumn;

/**
 * The target is JDBC, so every BSON value has to become a JDBC value before it reaches
 * {@code PreparedStatement.setObject}. The inference and the rendering both come from this
 * package, so the value a column is declared with and the value written into it cannot drift.
 */
class MongoValueRendererTest {

    @Test
    void objectIdsBecomeTheirHexText() {
        ObjectId id = new ObjectId("507f1f77bcf86cd799439011");

        assertThat(MongoValueRenderer.render(column(Types.VARCHAR, "ObjectId"), id))
                .isEqualTo("507f1f77bcf86cd799439011");
    }

    @Test
    void stringsGoToClobColumnsUnchanged() {
        assertThat(MongoValueRenderer.render(column(Types.CLOB, "String"), "中文 text"))
                .isEqualTo("中文 text");
    }

    @Test
    void datesBecomeSqlTimestamps() {
        Date date = Date.from(Instant.parse("2026-10-31T08:30:15.123Z"));

        Object rendered = MongoValueRenderer.render(column(Types.TIMESTAMP, "Date"), date);

        assertThat(rendered).isEqualTo(new java.sql.Timestamp(date.getTime()));
    }

    @Test
    void documentsBecomeJsonText() {
        Document value = new Document("a", 1).append("nested", new Document("b", "x"));

        Object rendered = MongoValueRenderer.render(column(Types.CLOB, "Document"), value);

        assertThat(rendered).isInstanceOf(String.class);
        assertThat(Document.parse((String) rendered)).isEqualTo(value);
    }

    @Test
    void arraysBecomeJsonText() {
        List<Object> value = List.of(1, "x", true);

        Object rendered = MongoValueRenderer.render(column(Types.CLOB, "Array"), value);

        assertThat(rendered).isInstanceOf(String.class);
        Document wrapper = Document.parse("{\"v\":" + rendered + "}");
        assertThat(wrapper.getList("v", Object.class)).isEqualTo(value);
    }

    @Test
    void booleansBecomeOneAndZero() {
        assertThat(MongoValueRenderer.render(column(Types.SMALLINT, "Boolean"), true))
                .isEqualTo(1);
        assertThat(MongoValueRenderer.render(column(Types.SMALLINT, "Boolean"), false))
                .isEqualTo(0);
    }

    @Test
    void binaryBecomesItsBytes() {
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);

        Object rendered = MongoValueRenderer.render(
                column(Types.BLOB, "Binary"), new Binary(bytes));

        assertThat(rendered).isEqualTo(bytes);
    }

    @Test
    void bsonTimestampsBecomeTheirSeconds() {
        assertThat(MongoValueRenderer.render(
                column(Types.BIGINT, "BsonTimestamp"), new BsonTimestamp(1700000000, 1)))
                .isEqualTo(1700000000L);
    }

    @Test
    void numericValuesAreConvertedToTheWidenedType() {
        assertThat(MongoValueRenderer.render(column(Types.BIGINT, "Int64"), 7))
                .isEqualTo(7L);
        assertThat(MongoValueRenderer.render(column(Types.DOUBLE, "Double"), 7))
                .isEqualTo(7.0d);
        assertThat(MongoValueRenderer.render(
                column(Types.DECIMAL, "Decimal128"), Decimal128.parse("1.25")))
                .isEqualTo(new BigDecimal("1.25"));
        assertThat(MongoValueRenderer.render(column(Types.DECIMAL, "Decimal128"), 3))
                .isEqualTo(new BigDecimal("3"));
    }

    @Test
    void mixedColumnsBecomeReadableText() {
        EtlSourceColumn mixed = column(Types.CLOB, "Mixed");

        assertThat(MongoValueRenderer.render(mixed, 1)).isEqualTo("1");
        assertThat(MongoValueRenderer.render(mixed, true)).isEqualTo("true");
        assertThat(MongoValueRenderer.render(mixed,
                new ObjectId("507f1f77bcf86cd799439011")))
                .isEqualTo("507f1f77bcf86cd799439011");
        assertThat(MongoValueRenderer.render(mixed,
                Date.from(Instant.parse("2026-10-31T08:30:15Z"))))
                .isEqualTo("2026-10-31T08:30:15Z");
    }

    @Test
    void nullStaysNullWhateverTheColumnType() {
        assertThat(MongoValueRenderer.render(column(Types.INTEGER, "Int32"), null)).isNull();
        assertThat(MongoValueRenderer.render(column(Types.CLOB, "Mixed"), null)).isNull();
        assertThat(MongoValueRenderer.render(column(Types.TIMESTAMP, "Date"), null)).isNull();
    }

    private static EtlSourceColumn column(int jdbcType, String typeName) {
        return new EtlSourceColumn("v", jdbcType, typeName, null, null, true);
    }
}
