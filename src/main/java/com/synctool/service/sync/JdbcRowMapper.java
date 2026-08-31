package com.synctool.service.sync;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/** Reads rows into portable maps and binds them back onto prepared statements. */
@Slf4j
public final class JdbcRowMapper {

    private JdbcRowMapper() {
    }

    /** Column names in result-set order. */
    public static List<String> columnNames(ResultSetMetaData md) throws SQLException {
        List<String> names = new ArrayList<>(md.getColumnCount());
        for (int i = 1; i <= md.getColumnCount(); i++) {
            // getColumnLabel honours aliases; getColumnName does not on every driver.
            names.add(md.getColumnLabel(i));
        }
        return names;
    }

    /** JDBC types in result-set order, used to bind nulls with the right type. */
    public static List<Integer> columnTypes(ResultSetMetaData md) throws SQLException {
        List<Integer> types = new ArrayList<>(md.getColumnCount());
        for (int i = 1; i <= md.getColumnCount(); i++) {
            types.add(md.getColumnType(i));
        }
        return types;
    }

    /**
     * Materializes the current row.
     *
     * <p>LOBs are read eagerly into byte arrays and strings, because a {@code Blob} handle
     * becomes invalid once the result set advances, and rows are buffered into batches
     * before being written.
     */
    public static Map<String, Object> readRow(ResultSet rs, List<String> columns,
                                              List<Integer> types) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            int idx = i + 1;
            int type = types.get(i);
            Object value;
            switch (type) {
                case Types.BLOB:
                case Types.LONGVARBINARY:
                case Types.VARBINARY:
                case Types.BINARY:
                    value = rs.getBytes(idx);
                    break;
                case Types.CLOB:
                case Types.NCLOB:
                case Types.LONGVARCHAR:
                case Types.LONGNVARCHAR:
                    value = rs.getString(idx);
                    break;
                case Types.TIMESTAMP:
                case Types.TIMESTAMP_WITH_TIMEZONE:
                    value = rs.getTimestamp(idx);
                    break;
                case Types.DATE:
                    value = rs.getDate(idx);
                    break;
                case Types.TIME:
                case Types.TIME_WITH_TIMEZONE:
                    value = rs.getTime(idx);
                    break;
                default:
                    value = rs.getObject(idx);
            }
            row.put(columns.get(i), rs.wasNull() ? null : value);
        }
        return row;
    }

    /**
     * Binds values onto a statement in the given order.
     *
     * @param bindOrder column names in the order the statement expects them, which may
     *                  repeat a column when the dialect names it more than once
     * @param types     JDBC type per column name, so nulls carry a type the driver accepts
     */
    public static void bind(PreparedStatement ps, Map<String, Object> row, List<String> bindOrder,
                            Map<String, Integer> types) throws SQLException {
        for (int i = 0; i < bindOrder.size(); i++) {
            String column = bindOrder.get(i);
            Object value = row.get(column);
            int idx = i + 1;
            if (value == null) {
                // Some drivers (Oracle in particular) reject setObject(idx, null) without a type.
                Integer type = types.get(column);
                ps.setNull(idx, type == null ? Types.NULL : type);
            } else {
                bindValue(ps, idx, value);
            }
        }
    }

    private static void bindValue(PreparedStatement ps, int idx, Object value) throws SQLException {
        // Normalize the java.time types some drivers return so every target accepts them.
        if (value instanceof Instant) {
            ps.setTimestamp(idx, Timestamp.from((Instant) value));
        } else if (value instanceof LocalDateTime) {
            ps.setTimestamp(idx, Timestamp.valueOf((LocalDateTime) value));
        } else if (value instanceof java.time.OffsetDateTime) {
            ps.setTimestamp(idx, Timestamp.from(((java.time.OffsetDateTime) value).toInstant()));
        } else if (value instanceof java.time.ZonedDateTime) {
            ps.setTimestamp(idx, Timestamp.from(((java.time.ZonedDateTime) value).toInstant()));
        } else if (value instanceof java.time.LocalDate) {
            ps.setDate(idx, java.sql.Date.valueOf((java.time.LocalDate) value));
        } else if (value instanceof java.time.LocalTime) {
            ps.setTime(idx, java.sql.Time.valueOf((java.time.LocalTime) value));
        } else if (value instanceof byte[]) {
            ps.setBytes(idx, (byte[]) value);
        } else {
            ps.setObject(idx, value);
        }
    }

    /** Renders a cursor value as text for durable storage in the progress row. */
    public static String cursorToString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp) {
            // ISO-8601 in UTC, so the stored value is unambiguous across restarts and
            // across a server timezone change.
            return ((Timestamp) value).toInstant().toString();
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate().atStartOfDay()
                    .toInstant(ZoneOffset.UTC).toString();
        }
        if (value instanceof Instant) {
            return value.toString();
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toInstant(ZoneOffset.UTC).toString();
        }
        return String.valueOf(value);
    }

    /**
     * Parses a stored cursor back into a bindable value.
     *
     * @param jdbcType the type of the cursor column, which decides the representation
     */
    public static Object cursorFromString(String stored, int jdbcType) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (CursorTypes.isTemporal(jdbcType)) {
            try {
                return Timestamp.from(Instant.parse(stored));
            } catch (Exception e) {
                // Tolerate a value written as a plain SQL timestamp by an older version.
                try {
                    return Timestamp.valueOf(stored);
                } catch (Exception ignored) {
                    log.warn("Unparseable stored cursor '{}'; treating as absent", stored);
                    return null;
                }
            }
        }
        try {
            return Long.parseLong(stored.trim());
        } catch (NumberFormatException e) {
            try {
                return new java.math.BigDecimal(stored.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Unparseable numeric cursor '{}'; treating as absent", stored);
                return null;
            }
        }
    }

    /** Small helper so the mapper does not depend on the monitor package. */
    static final class CursorTypes {
        static boolean isTemporal(int jdbcType) {
            return jdbcType == Types.TIMESTAMP
                    || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE
                    || jdbcType == Types.DATE
                    || jdbcType == Types.TIME;
        }
    }
}
