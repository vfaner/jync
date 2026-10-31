package com.qqmu.jync.service.etl.mongo;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import com.qqmu.jync.service.etl.EtlSourceColumn;
import com.qqmu.jync.service.etl.EtlSqlSupport;

/**
 * Builds the target columns of a MongoDB extraction from a bounded sample.
 *
 * <p>Only top-level fields become columns. A field that appears in any sample is a column even
 * when other samples omit it; a field that is null everywhere is still a text column so the
 * load has somewhere to write its nulls. Numeric BSON types widen among themselves; any other
 * mix becomes text, and {@link MongoValueRenderer} renders every value of that column as text.
 *
 * <p>Field names must survive being an unquoted column name in every supported target, and two
 * names that differ only in case would fold into the same column — both are refused here, before
 * anything on the target is deleted.
 *
 * <p>An empty result means the sample contained no fields at all. The caller decides whether
 * that is an error ({@code error.etl.mongo.sample.empty}); the inferrer never invents a column.
 */
public final class MongoSchemaInferrer {

    private MongoSchemaInferrer() {
    }

    public static List<EtlSourceColumn> infer(List<Document> samples) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        Map<String, EnumSet<Kind>> kinds = new LinkedHashMap<>();
        Map<String, String> foldedNames = new LinkedHashMap<>();
        for (Document sample : samples) {
            if (sample == null) {
                continue;
            }
            for (Map.Entry<String, Object> field : sample.entrySet()) {
                String name = field.getKey();
                if (name == null || !EtlSqlSupport.PLAIN_IDENTIFIER.matcher(name).matches()) {
                    throw new MongoSchemaException("error.etl.mongo.field.invalid", name);
                }
                String folded = name.toLowerCase(Locale.ROOT);
                String existing = foldedNames.putIfAbsent(folded, name);
                if (existing != null && !existing.equals(name)) {
                    throw new MongoSchemaException("error.etl.mongo.field.duplicate", name);
                }
                if (field.getValue() != null) {
                    kinds.computeIfAbsent(name, ignored -> EnumSet.noneOf(Kind.class))
                            .add(Kind.of(field.getValue()));
                } else {
                    kinds.computeIfAbsent(name, ignored -> EnumSet.noneOf(Kind.class));
                }
            }
        }

        List<String> ordered = new ArrayList<>(kinds.keySet());
        ordered.sort(Comparator.comparing((String name) -> !"_id".equals(name))
                .thenComparing(Comparator.naturalOrder()));

        List<EtlSourceColumn> columns = new ArrayList<>(ordered.size());
        for (String name : ordered) {
            columns.add(kinds.get(name).isEmpty()
                    ? Kind.NULL.column(name)
                    : narrow(kinds.get(name)).column(name));
        }
        return columns;
    }

    /** Resolves a set of observed BSON kinds to the one type the column is declared with. */
    private static Kind narrow(Set<Kind> observed) {
        if (observed.isEmpty()) {
            return Kind.NULL;
        }
        if (observed.size() == 1) {
            return observed.iterator().next();
        }
        Kind widened = null;
        for (Kind kind : observed) {
            if (!kind.numeric()) {
                widened = null;
                break;
            }
            widened = widened == null || kind.rank() > widened.rank() ? kind : widened;
        }
        if (widened != null) {
            return widened;
        }
        if (observed.stream().allMatch(kind -> kind == Kind.INT64 || kind == Kind.TIMESTAMP)) {
            // A BSON timestamp and a plain long are both stored as BIGINT; keeping the
            // timestamp type name documents why the column is not simply Int64.
            return Kind.TIMESTAMP;
        }
        return Kind.MIXED;
    }

    private enum Kind {
        OBJECT_ID(Types.VARCHAR, "ObjectId", 24, null, false, 0),
        STRING(Types.CLOB, "String", null, null, false, 0),
        INT32(Types.INTEGER, "Int32", null, null, true, 1),
        INT64(Types.BIGINT, "Int64", null, null, true, 2),
        DOUBLE(Types.DOUBLE, "Double", null, null, true, 3),
        DECIMAL(Types.DECIMAL, "Decimal128", 38, 10, true, 4),
        BOOLEAN(Types.SMALLINT, "Boolean", null, null, false, 0),
        DATE(Types.TIMESTAMP, "Date", null, null, false, 0),
        BINARY(Types.BLOB, "Binary", null, null, false, 0),
        DOCUMENT(Types.CLOB, "Document", null, null, false, 0),
        ARRAY(Types.CLOB, "Array", null, null, false, 0),
        TIMESTAMP(Types.BIGINT, "BsonTimestamp", null, null, false, 0),
        NULL(Types.CLOB, "Null", null, null, false, 0),
        MIXED(Types.CLOB, "Mixed", null, null, false, 0);

        private final int jdbcType;
        private final String typeName;
        private final Integer size;
        private final Integer decimalDigits;
        private final boolean numeric;
        private final int rank;

        Kind(int jdbcType, String typeName, Integer size, Integer decimalDigits,
             boolean numeric, int rank) {
            this.jdbcType = jdbcType;
            this.typeName = typeName;
            this.size = size;
            this.decimalDigits = decimalDigits;
            this.numeric = numeric;
            this.rank = rank;
        }

        boolean numeric() {
            return numeric;
        }

        int rank() {
            return rank;
        }

        EtlSourceColumn column(String name) {
            return new EtlSourceColumn(name, jdbcType, typeName, size, decimalDigits, true);
        }

        static Kind of(Object value) {
            if (value instanceof ObjectId) {
                return OBJECT_ID;
            }
            if (value instanceof String) {
                return STRING;
            }
            if (value instanceof Integer) {
                return INT32;
            }
            if (value instanceof Long) {
                return INT64;
            }
            if (value instanceof Double) {
                return DOUBLE;
            }
            if (value instanceof Decimal128) {
                return DECIMAL;
            }
            if (value instanceof Boolean) {
                return BOOLEAN;
            }
            if (value instanceof Date) {
                return DATE;
            }
            if (value instanceof Binary || value instanceof byte[]) {
                return BINARY;
            }
            if (value instanceof Document || value instanceof Map) {
                return DOCUMENT;
            }
            if (value instanceof List) {
                return ARRAY;
            }
            if (value instanceof BsonTimestamp) {
                return TIMESTAMP;
            }
            throw new MongoSchemaException("error.etl.mongo.field.invalid",
                    value.getClass().getSimpleName());
        }
    }
}
