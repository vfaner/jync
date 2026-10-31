package com.qqmu.jync.service.etl.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * MongoDB has no schema, so the target table is built from a bounded sample. The sample is
 * taken with the same filter the load uses, in {@code _id} order, so the inference is stable
 * between precheck and run.
 */
class MongoSchemaInferrerTest {

    @Test
    void idComesFirstAndTheRestFollowNameOrder() {
        List<EtlSourceColumn> columns = MongoSchemaInferrer.infer(List.of(
                new Document("z", 1)
                        .append("_id", new ObjectId("507f1f77bcf86cd799439011"))
                        .append("a", "x"),
                new Document("b", 2).append("a", "y")));

        assertThat(names(columns)).containsExactly("_id", "a", "b", "z");
    }

    @Test
    void mapsEachBsonTypeToItsJdbcType() {
        Document sample = new Document()
                .append("id", new ObjectId("507f1f77bcf86cd799439011"))
                .append("s", "text")
                .append("i", 7)
                .append("l", 7L)
                .append("d", 1.5d)
                .append("dec", Decimal128.parse("1.23"))
                .append("b", true)
                .append("dt", Date.from(Instant.parse("2026-10-31T08:30:15Z")))
                .append("bin", new Binary(new byte[] {1, 2}))
                .append("doc", new Document("x", 1))
                .append("arr", List.of(1, 2))
                .append("ts", new BsonTimestamp(1700000000, 1));

        List<EtlSourceColumn> columns = MongoSchemaInferrer.infer(List.of(sample));

        assertColumn(columns, "id", Types.VARCHAR, "ObjectId", 24, null);
        assertColumn(columns, "s", Types.CLOB, "String", null, null);
        assertColumn(columns, "i", Types.INTEGER, "Int32", null, null);
        assertColumn(columns, "l", Types.BIGINT, "Int64", null, null);
        assertColumn(columns, "d", Types.DOUBLE, "Double", null, null);
        assertColumn(columns, "dec", Types.DECIMAL, "Decimal128", 38, 10);
        assertColumn(columns, "b", Types.SMALLINT, "Boolean", null, null);
        assertColumn(columns, "dt", Types.TIMESTAMP, "Date", null, null);
        assertColumn(columns, "bin", Types.BLOB, "Binary", null, null);
        assertColumn(columns, "doc", Types.CLOB, "Document", null, null);
        assertColumn(columns, "arr", Types.CLOB, "Array", null, null);
        assertColumn(columns, "ts", Types.BIGINT, "BsonTimestamp", null, null);
    }

    @Test
    void aFieldThatIsNullInEverySampleBecomesAClobColumn() {
        List<EtlSourceColumn> columns = MongoSchemaInferrer.infer(List.of(
                new Document("n", null), new Document("n", null)));

        assertColumn(columns, "n", Types.CLOB, "Null", null, null);
    }

    @Test
    void aFieldMissingFromSomeSamplesIsStillAColumn() {
        List<EtlSourceColumn> columns = MongoSchemaInferrer.infer(List.of(
                new Document("a", 1), new Document("b", 2)));

        assertThat(names(columns)).containsExactly("a", "b");
        assertColumn(columns, "a", Types.INTEGER, "Int32", null, null);
        assertColumn(columns, "b", Types.INTEGER, "Int32", null, null);
    }

    @Test
    void nullsDoNotWidenTheColumn() {
        List<EtlSourceColumn> columns = MongoSchemaInferrer.infer(List.of(
                new Document("n", null), new Document("n", 3)));

        assertColumn(columns, "n", Types.INTEGER, "Int32", null, null);
    }

    @Test
    void numericTypesWidenToTheWiderOne() {
        assertColumn(MongoSchemaInferrer.infer(List.of(
                        new Document("n", 1), new Document("n", 2L))),
                "n", Types.BIGINT, "Int64", null, null);

        assertColumn(MongoSchemaInferrer.infer(List.of(
                        new Document("n", 1), new Document("n", 2.5d))),
                "n", Types.DOUBLE, "Double", null, null);

        assertColumn(MongoSchemaInferrer.infer(List.of(
                        new Document("n", 2.5d), new Document("n", Decimal128.parse("3.5")))),
                "n", Types.DECIMAL, "Decimal128", 38, 10);

        assertColumn(MongoSchemaInferrer.infer(List.of(
                        new Document("n", 1L), new Document("n", new BsonTimestamp(1700000000, 1)))),
                "n", Types.BIGINT, "BsonTimestamp", null, null);
    }

    @Test
    void aNonNumericMixBecomesText() {
        List<EtlSourceColumn> columns = MongoSchemaInferrer.infer(List.of(
                new Document("v", 1), new Document("v", "one")));

        assertColumn(columns, "v", Types.CLOB, "Mixed", null, null);
    }

    @Test
    void everyInferredColumnIsNullable() {
        List<EtlSourceColumn> columns = MongoSchemaInferrer.infer(List.of(new Document("a", 1)));

        assertThat(columns).allMatch(EtlSourceColumn::nullable);
    }

    @Test
    void rejectsAFieldNameThatCannotBeAnUnquotedColumn() {
        assertThatThrownBy(() -> MongoSchemaInferrer.infer(List.of(new Document("bad-name", 1))))
                .isInstanceOf(MongoSchemaException.class)
                .hasMessage("error.etl.mongo.field.invalid: bad-name");
    }

    @Test
    void rejectsFieldNamesThatDifferOnlyInCase() {
        Document sample = new Document("a", 1).append("A", 2);

        assertThatThrownBy(() -> MongoSchemaInferrer.infer(List.of(sample)))
                .isInstanceOf(MongoSchemaException.class)
                .hasMessage("error.etl.mongo.field.duplicate: A");
    }

    @Test
    void emptySamplesProduceNoColumns() {
        assertThat(MongoSchemaInferrer.infer(List.of())).isEmpty();
        assertThat(MongoSchemaInferrer.infer(List.of(new Document()))).isEmpty();
    }

    private static List<String> names(List<EtlSourceColumn> columns) {
        return columns.stream().map(EtlSourceColumn::name).toList();
    }

    private static void assertColumn(List<EtlSourceColumn> columns, String name, int jdbcType,
                                     String typeName, Integer size, Integer decimalDigits) {
        EtlSourceColumn column = columns.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no column " + name + " in " + names(columns)));
        assertThat(column.jdbcType()).as(name + " jdbcType").isEqualTo(jdbcType);
        assertThat(column.typeName()).as(name + " typeName").isEqualTo(typeName);
        assertThat(column.size()).as(name + " size").isEqualTo(size);
        assertThat(column.decimalDigits()).as(name + " decimalDigits").isEqualTo(decimalDigits);
    }
}
