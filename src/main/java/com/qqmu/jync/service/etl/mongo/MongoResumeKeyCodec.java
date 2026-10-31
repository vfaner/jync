package com.qqmu.jync.service.etl.mongo;

import java.sql.Types;
import java.time.Instant;
import java.util.Date;

import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

/**
 * Stores a Mongo resume-key value as text in the shared ETL checkpoint table.
 *
 * <p>The JDBC sources store the value's SQL representation and its {@link Types} code, so a
 * Mongo key follows the same shape: the codec maps the inferred column type back to a BSON
 * value for the {@code $gt} condition. Types whose textual form cannot reproduce the BSON
 * comparison (a BSON timestamp becomes a Long, a document has no ordering) are refused
 * up front instead of silently resuming from the wrong place.
 */
public final class MongoResumeKeyCodec {

    /** Matches {@code EtlCheckpoint}'s value column. */
    public static final int MAX_ENCODED_LENGTH = 1024;

    private MongoResumeKeyCodec() {
    }

    public static String encode(Object value) {
        if (value == null) {
            return null;
        }
        String encoded;
        if (value instanceof ObjectId id) {
            encoded = id.toHexString();
        } else if (value instanceof String text) {
            encoded = text;
        } else if (value instanceof Integer || value instanceof Long || value instanceof Double) {
            encoded = value.toString();
        } else if (value instanceof Decimal128 decimal) {
            encoded = decimal.toString();
        } else if (value instanceof Date date) {
            encoded = Instant.ofEpochMilli(date.getTime()).toString();
        } else if (value instanceof Boolean bool) {
            encoded = bool.toString();
        } else {
            throw new IllegalArgumentException("error.etl.mongo.resume.type");
        }
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("error.etl.mongo.resume.tooLong");
        }
        return encoded;
    }

    /**
     * @param text     the checkpoint value
     * @param jdbcType the inferred column type, which decides how to read the text
     */
    public static Object decode(String text, int jdbcType) {
        if (text == null) {
            return null;
        }
        try {
            switch (jdbcType) {
                case Types.VARCHAR:
                case Types.NVARCHAR:
                case Types.CHAR:
                case Types.NCHAR:
                    return new ObjectId(text);
                case Types.CLOB:
                case Types.NCLOB:
                case Types.LONGVARCHAR:
                case Types.LONGNVARCHAR:
                    return text;
                case Types.INTEGER:
                    return Integer.valueOf(text);
                case Types.BIGINT:
                    return Long.valueOf(text);
                case Types.DOUBLE:
                case Types.FLOAT:
                case Types.REAL:
                    return Double.valueOf(text);
                case Types.DECIMAL:
                case Types.NUMERIC:
                    return Decimal128.parse(text);
                case Types.TIMESTAMP:
                case Types.DATE:
                case Types.TIME:
                    return Date.from(Instant.parse(text));
                case Types.SMALLINT:
                case Types.TINYINT:
                case Types.BOOLEAN:
                    if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
                        return Boolean.TRUE;
                    }
                    if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
                        return Boolean.FALSE;
                    }
                    throw new IllegalArgumentException("error.etl.mongo.resume.invalid");
                default:
                    throw new IllegalArgumentException("error.etl.mongo.resume.invalid");
            }
        } catch (IllegalArgumentException e) {
            if ("error.etl.mongo.resume.invalid".equals(e.getMessage())) {
                throw e;
            }
            throw new IllegalArgumentException("error.etl.mongo.resume.invalid", e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("error.etl.mongo.resume.invalid", e);
        }
    }
}
