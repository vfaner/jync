package com.qqmu.jync.service.etl.mongo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import com.qqmu.jync.service.etl.EtlSourceColumn;

/**
 * Converts one BSON value into the JDBC value its inferred column expects.
 *
 * <p>The runner writes every non-null value with {@code PreparedStatement.setObject}, so the
 * renderer — not the target driver — owns the conversion. A numeric column widened during
 * inference receives the wider Java type, and a text column receives readable text: dates as
 * ISO-8601 instants, nested documents and arrays as relaxed Extended JSON, binary as base64.
 */
public final class MongoValueRenderer {

    private static final Base64.Encoder BASE64 = Base64.getEncoder();

    private MongoValueRenderer() {
    }

    public static Object render(EtlSourceColumn column, Object value) {
        if (value == null) {
            return null;
        }
        switch (column.jdbcType()) {
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.CHAR:
            case Types.NCHAR:
                return value instanceof ObjectId id ? id.toHexString() : String.valueOf(value);
            case Types.CLOB:
            case Types.NCLOB:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                return text(value);
            case Types.INTEGER:
                return value instanceof Number number ? number.intValue() : text(value);
            case Types.BIGINT:
                if (value instanceof BsonTimestamp timestamp) {
                    // getTime() is int; the column is BIGINT, so widen the Java value too.
                    return (long) timestamp.getTime();
                }
                return value instanceof Number number ? number.longValue() : text(value);
            case Types.SMALLINT:
            case Types.TINYINT:
                if (value instanceof Boolean bool) {
                    return bool ? 1 : 0;
                }
                return value instanceof Number number ? number.intValue() : text(value);
            case Types.DOUBLE:
            case Types.FLOAT:
            case Types.REAL:
                return value instanceof Number number ? number.doubleValue() : text(value);
            case Types.DECIMAL:
            case Types.NUMERIC:
                return decimal(value);
            case Types.TIMESTAMP:
            case Types.DATE:
            case Types.TIME:
                return value instanceof Date date ? new Timestamp(date.getTime()) : text(value);
            case Types.BLOB:
                if (value instanceof Binary binary) {
                    return binary.getData();
                }
                return value instanceof byte[] bytes ? bytes : text(value).getBytes(StandardCharsets.UTF_8);
            default:
                return text(value);
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Decimal128 decimal) {
            return decimal.bigDecimalValue();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(text(value));
    }

    /** The text form shared by mixed columns and by fields that are structurally JSON. */
    private static String text(Object value) {
        if (value instanceof ObjectId id) {
            return id.toHexString();
        }
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime()).toString();
        }
        if (value instanceof Document document) {
            return document.toJson();
        }
        if (value instanceof List<?> list) {
            return jsonArray(list);
        }
        if (value instanceof BsonTimestamp timestamp) {
            return Long.toString(timestamp.getTime());
        }
        if (value instanceof Binary binary) {
            return BASE64.encodeToString(binary.getData());
        }
        if (value instanceof byte[] bytes) {
            return BASE64.encodeToString(bytes);
        }
        return String.valueOf(value);
    }

    /**
     * The driver serializes a document directly but not a bare array, so wrap it, then cut the
     * wrapper away. Only the first colon and the closing brace are structural; everything
     * between them is the array's own JSON.
     */
    private static String jsonArray(List<?> list) {
        String wrapped = new Document("v", list).toJson();
        int colon = wrapped.indexOf(':');
        int end = wrapped.lastIndexOf('}');
        if (colon < 0 || end <= colon) {
            return wrapped;
        }
        return wrapped.substring(colon + 1, end).trim();
    }
}
