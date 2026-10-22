package com.qqmu.jync.service.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Locale;

import com.qqmu.jync.model.DatabaseType;

/**
 * Reads product-specific column types into portable Java values.
 *
 * <p>{@code getObject} hands back driver-private classes for several vendor types —
 * Oracle's {@code XMLTYPE}/{@code INTERVAL}, SQL Server's {@code DateTimeOffset},
 * geometry objects — and no non-native target driver can bind those. The text or WKB
 * payload is what every target can store, and it matches the portable type the column is
 * mapped to.
 */
public final class JdbcValueNormalizer {

    /** Sentinel: the column is not a vendor type; the caller keeps its normal read path. */
    public static final Object UNHANDLED = new Object();

    private JdbcValueNormalizer() {
    }

    public static Object read(ResultSet rs, int idx, int jdbcType, String typeName,
                       DatabaseType sourceType) throws SQLException {
        String name = normalize(typeName);
        if (name == null) {
            return UNHANDLED;
        }
        switch (name) {
            case "XMLTYPE":
                return xmlTypeText(rs, idx);
            case "UNIQUEIDENTIFIER":
            case "XML":
            case "SQL_VARIANT":
            case "ROWID":
            case "INTERVALYM":
            case "INTERVALDS":
            case "INTERVAL YEAR TO MONTH":
            case "INTERVAL DAY TO SECOND":
            case "ENUM":
            case "SET":
            case "JSON":
            case "JSONB":
                return rs.getString(idx);
            case "MONEY":
            case "SMALLMONEY":
            case "DECFLOAT":
                return rs.getBigDecimal(idx);
            case "GEOMETRY":
            case "GEOGRAPHY":
                // WKB bytes: portable, and what a BLOB target column stores.
                return rs.getBytes(idx);
            case "DATETIMEOFFSET":
            case "TIMESTAMPTZ":
                return offsetDateTime(rs, idx);
            case "TIMESTAMPLTZ":
                // Session-timezone dependent; the timestamp the driver renders is the most
                // faithful portable form.
                return rs.getTimestamp(idx);
            default:
                return UNHANDLED;
        }
    }

    /** True when {@link #read} has a rule for this type name; lets callers skip the lookup. */
    public static boolean handles(String typeName) {
        String name = normalize(typeName);
        if (name == null) {
            return false;
        }
        switch (name) {
            case "UNIQUEIDENTIFIER":
            case "XML":
            case "XMLTYPE":
            case "SQL_VARIANT":
            case "ROWID":
            case "INTERVALYM":
            case "INTERVALDS":
            case "INTERVAL YEAR TO MONTH":
            case "INTERVAL DAY TO SECOND":
            case "ENUM":
            case "SET":
            case "JSON":
            case "JSONB":
            case "MONEY":
            case "SMALLMONEY":
            case "DECFLOAT":
            case "GEOMETRY":
            case "GEOGRAPHY":
            case "DATETIMEOFFSET":
            case "TIMESTAMPTZ":
            case "TIMESTAMPLTZ":
                return true;
            default:
                return false;
        }
    }

    /**
     * Oracle's thin driver needs the separate {@code xdb} jar to render {@code XMLTYPE} as
     * text; without it {@code getString} throws {@link NoClassDefFoundError} for
     * {@code oracle.xdb.XMLType}. The column's CLOB representation is readable either way,
     * so that is the fallback. Reading it whole is deliberate: the target column is a
     * character LOB and the text is the value.
     */
    private static Object xmlTypeText(ResultSet rs, int idx) throws SQLException {
        try {
            return rs.getString(idx);
        } catch (SQLException | NoClassDefFoundError e) {
            java.sql.Clob clob = rs.getClob(idx);
            if (clob == null) {
                return null;
            }
            try {
                return clob.getSubString(1, (int) clob.length());
            } finally {
                clob.free();
            }
        }
    }

    /**
     * {@code getObject(OffsetDateTime.class)} keeps the offset; drivers that do not support
     * it fall back to the driver's own text, which an ISO-aware target parses.
     */
    private static Object offsetDateTime(ResultSet rs, int idx) throws SQLException {
        try {
            OffsetDateTime value = rs.getObject(idx, OffsetDateTime.class);
            return value;
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
            return rs.getString(idx);
        }
    }

    private static String normalize(String typeName) {
        return typeName == null ? null : typeName.trim().toUpperCase(Locale.ROOT);
    }
}
