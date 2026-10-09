package com.qqmu.jync.service.sync;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
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
            // ISO date text, not an instant: a DATE column has no time of day, so encoding
            // midnight UTC and decoding the instant through the target's timezone could move
            // the wall date a day. The string round-trips through Date.valueOf with no
            // timezone anywhere.
            return ((java.sql.Date) value).toLocalDate().toString();
        }
        if (value instanceof java.sql.Time) {
            // "HH:mm:ss" — no date part to disambiguate; this is what cursorFromString
            // expects back for a TIME cursor column.
            return value.toString();
        }
        if (value instanceof java.time.LocalTime) {
            // Normalize through java.sql.Time so the text always carries seconds
            // (LocalTime.toString() omits ":00" — Time.valueOf would reject it).
            return java.sql.Time.valueOf((java.time.LocalTime) value).toString();
        }
        if (value instanceof java.time.LocalDate) {
            return value.toString();
        }
        if (value instanceof Instant) {
            return value.toString();
        }
        if (value instanceof LocalDateTime) {
            // Keep the wall-clock text itself. A LocalDateTime is explicitly zone-less: the
            // old toInstant(UTC) invented a UTC zone on write, but on bind the Timestamp went
            // back through the driver in its own session/JVM zone, so the window edge shifted
            // by that offset and rows fell out of the window. Normalize through
            // Timestamp.valueOf: LocalDateTime.toString() omits a zero seconds component,
            // which the parser would then reject.
            return Timestamp.valueOf((LocalDateTime) value).toString();
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
            if (jdbcType == Types.TIME) {
                // TIME columns are stored as "HH:mm:ss" (see cursorToString); an
                // ISO instant is meaningless for them, so parse the time form only.
                try {
                    return java.sql.Time.valueOf(stored.trim());
                } catch (Exception e) {
                    log.warn("Unparseable stored time cursor '{}'; treating as absent", stored);
                    return null;
                }
            }
            if (jdbcType == Types.DATE) {
                // DATE columns are ISO date text ("2026-01-01").
                try {
                    return java.sql.Date.valueOf(stored.trim());
                } catch (Exception ignored) {
                    // Tolerate a pre-fix cursor written as a midnight-UTC instant; decode it
                    // in UTC, symmetric with how the old writer encoded it.
                    try {
                        return java.sql.Date.valueOf(Instant.parse(stored.trim())
                                .atZone(java.time.ZoneOffset.UTC).toLocalDate());
                    } catch (Exception e) {
                        log.warn("Unparseable stored date cursor '{}'; treating as absent", stored);
                        return null;
                    }
                }
            }
            try {
                return Timestamp.from(Instant.parse(stored));
            } catch (Exception e) {
                // Tolerate wall-clock forms: a plain SQL timestamp ("yyyy-MM-dd HH:mm:ss")
                // written by an older version, or ISO LocalDateTime text ("...T...").
                try {
                    String spaced = stored.trim().replace('T', ' ');
                    return Timestamp.valueOf(spaced);
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
