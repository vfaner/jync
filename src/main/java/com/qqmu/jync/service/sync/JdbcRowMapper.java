package com.qqmu.jync.service.sync;

import java.math.BigDecimal;
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

    /** Database-specific type names in result-set order (e.g. "YEAR", "DATETIME"). */
    public static List<String> columnTypeNames(ResultSetMetaData md) throws SQLException {
        List<String> names = new ArrayList<>(md.getColumnCount());
        for (int i = 1; i <= md.getColumnCount(); i++) {
            names.add(md.getColumnTypeName(i));
        }
        return names;
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
        return readRow(rs, columns, types, null);
    }

    /**
     * As {@link #readRow(ResultSet, List, List)} but with the database type name per column,
     * so columns whose JDBC type misreports the value (MySQL YEAR and DATETIME) can be read
     * by their real type.
     */
    public static Map<String, Object> readRow(ResultSet rs, List<String> columns,
                                              List<Integer> types, List<String> typeNames)
            throws SQLException {
        return readRow(rs, columns, types, typeNames, null);
    }

    /**
     * As the 4-arg form but with the source product, so family-specific driver-private
     * values can be read as text: PostgreSQL enums ({@code Types.OTHER} named after the
     * enum) arrive as {@code PGobject} and arrays ({@code Types.ARRAY}, type name
     * {@code "_int4"}) as {@code PgArray}. The generated target columns are TEXT/CLOB, and
     * no non-PG driver — nor a PG text column — accepts those driver-private objects.
     */
    public static Map<String, Object> readRow(ResultSet rs, List<String> columns,
                                              List<Integer> types, List<String> typeNames,
                                              com.qqmu.jync.model.DatabaseType sourceType)
            throws SQLException {
        return readRowInto(rs, columns, types, typeNames, sourceType, new LinkedHashMap<>());
    }

    /**
     * As the 5-arg form, but filling a caller-owned map: every row of a large load would
     * otherwise allocate its own {@code LinkedHashMap}, and the write loops have no use for
     * the previous row once it has been bound. The map is cleared first, so the result is
     * indistinguishable from a freshly built one.
     *
     * @param row scratch map, reused across rows; cleared before filling
     */
    public static Map<String, Object> readRowInto(ResultSet rs, List<String> columns,
                                                  List<Integer> types, List<String> typeNames,
                                                  com.qqmu.jync.model.DatabaseType sourceType,
                                                  Map<String, Object> row)
            throws SQLException {
        return readRowInto(rs, columns, types, typeNames, sourceType, row,
                com.qqmu.jync.service.lob.LobReadPolicy.disabled(), null);
    }

    /**
     * As the 6-arg form, but with a per-table LOB read policy.
     *
     * <p>Columns the policy marks as large are read through
     * {@code getCharacterStream}/{@code getBinaryStream}, never {@code getString}/
     * {@code getBytes}: those are the calls that materialize the entire value. Values
     * below the inline threshold still come back as plain {@code String}/{@code byte[]},
     * so the batch path is unaffected for ordinary rows. A value that overflows becomes a
     * one-shot (or spilled) {@code LobValue}, and the writer must execute its row before
     * advancing the cursor.
     *
     * @param spillSession required only when the policy's large mode is {@code SPILL}
     */
    public static Map<String, Object> readRowInto(ResultSet rs, List<String> columns,
                                                  List<Integer> types, List<String> typeNames,
                                                  com.qqmu.jync.model.DatabaseType sourceType,
                                                  Map<String, Object> row,
                                                  com.qqmu.jync.service.lob.LobReadPolicy policy,
                                                  com.qqmu.jync.service.lob.LobSpillSession spillSession)
            throws SQLException {
        // Convenience for one-off reads: a caller looping over rows should build the plan
        // once outside the loop instead.
        return readRowInto(rs, RowReadPlan.of(columns, types, typeNames, policy),
                sourceType, row, spillSession);
    }

    /**
     * As the policy form, with the per-column decisions already resolved for this table.
     *
     * <p>The hot path passes a plan built once per table: the LOB mode and the
     * vendor-normalizer decision are functions of the column, so resolving them per row
     * only costs an allocation on every value.
     */
    public static Map<String, Object> readRowInto(ResultSet rs, RowReadPlan plan,
                                                  com.qqmu.jync.model.DatabaseType sourceType,
                                                  Map<String, Object> row,
                                                  com.qqmu.jync.service.lob.LobSpillSession spillSession)
            throws SQLException {
        row.clear();
        for (int i = 0; i < plan.size(); i++) {
            String column = plan.column(i);
            int idx = i + 1;
            int type = plan.type(i);
            String typeName = plan.typeName(i);
            com.qqmu.jync.service.lob.LobReadPolicy.Mode mode = plan.mode(i);
            Object value;
            if (mode == com.qqmu.jync.service.lob.LobReadPolicy.Mode.INLINE) {
                Object normalized = plan.vendorHandled(i)
                        ? JdbcValueNormalizer.read(rs, idx, type, typeName, sourceType)
                        : JdbcValueNormalizer.UNHANDLED;
                value = normalized != JdbcValueNormalizer.UNHANDLED
                        ? normalized
                        : readValue(rs, idx, type, typeName, sourceType);
            } else if (isTextType(type)) {
                try {
                    java.io.Reader reader = rs.getCharacterStream(idx);
                    value = reader == null ? null : com.qqmu.jync.service.lob.LobStreams
                            .readText(reader, plan.policy(), spillSession);
                } catch (java.io.IOException e) {
                    throw new SQLException("Failed to stream text column " + column, e);
                }
            } else if (isBinaryType(type)) {
                try {
                    java.io.InputStream stream = rs.getBinaryStream(idx);
                    value = stream == null ? null : com.qqmu.jync.service.lob.LobStreams
                            .readBinary(stream, plan.policy(), spillSession);
                } catch (java.io.IOException e) {
                    throw new SQLException("Failed to stream binary column " + column, e);
                }
            } else {
                // A size above the threshold on a non-LOB type (INTEGER with a bogus
                // precision, say) has no safe stream getter; read it as usual.
                value = readValue(rs, idx, type, typeName, sourceType);
            }
            row.put(column, value);
        }
        return row;
    }

    private static boolean isTextType(int type) {
        switch (type) {
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
                return true;
            default:
                return false;
        }
    }

    private static boolean isBinaryType(int type) {
        switch (type) {
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return true;
            default:
                return false;
        }
    }

    /**
     * Reads one column with the getter its JDBC type needs. Split out of {@link #readRowInto}
     * so the vendor-type normalization above stays the first thing the reader sees.
     */
    private static Object readValue(ResultSet rs, int idx, int type, String typeName,
                                    com.qqmu.jync.model.DatabaseType sourceType)
            throws SQLException {
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
                if (isMysqlDatetime(typeName)) {
                    // Connector/J converts DATETIME from the server session zone to the
                    // JVM zone inside getTimestamp, shifting the wall clock. The server
                    // literal is the displayed wall time and must survive verbatim; the
                    // bind side casts it explicitly (?::timestamp on PG-family targets).
                    value = rs.getString(idx);
                } else {
                    value = rs.getTimestamp(idx);
                }
                break;
            case Types.DATE:
                if (isMysqlYear(typeName)) {
                    // YEAR reports DATE but carries an integer year; getDate produced a
                    // "2026-01-01" literal the integer target could not parse. getInt is
                    // the only primitive read here, so it is the only one that needs the
                    // wasNull check — every object-returning getter already yields null
                    // for SQL NULL, and checking all of them cost a call per column.
                    int year = rs.getInt(idx);
                    value = rs.wasNull() ? null : year;
                } else {
                    value = rs.getDate(idx);
                }
                break;
            case Types.OTHER:
                if (isPostgresFamily(sourceType)) {
                    // Every PG-only OTHER (json/jsonb/uuid/xml plus enums and geometry
                    // types) builds onto a TEXT/CLOB target — getObject would hand the
                    // target driver a private PGobject it cannot infer. The text payload
                    // is the universal landing form; json/uuid also bind to native target
                    // columns through their assignment casts.
                    value = rs.getString(idx);
                } else if (isPgTextBorneOther(typeName)) {
                    // Source family unknown (older call paths): the known text-borne
                    // names still take the safe read.
                    value = rs.getString(idx);
                } else {
                    value = rs.getObject(idx);
                }
                break;
            case Types.ARRAY:
                if (isPostgresFamily(sourceType)) {
                    // PG arrays report ARRAY with internal names like "_int4"; the
                    // generated column is TEXT/CLOB and accepts the "{1,2,3}" literal,
                    // while a PgArray object is rejected by every target driver.
                    value = rs.getString(idx);
                } else {
                    value = rs.getObject(idx);
                }
                break;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                // Ruling R5: rs.getTime cannot carry MySQL's TIME domain — Connector/J's
                // SqlTimeValueFactory REJECTS hours<0 or >=24 with a DataReadException
                // (which parked whole baselines over one extreme row), and the write side
                // wraps them through a SimpleDateFormat("HH:mm:ss"). getString yields the
                // server's literal (sign, full hour range, fraction), which bindValue
                // sends back verbatim; normal values keep their exact stored form.
                value = rs.getString(idx);
                break;
            default:
                value = rs.getObject(idx);
        }
        return value;
    }

    private static boolean isMysqlYear(String typeName) {
        return typeName != null && "YEAR".equalsIgnoreCase(typeName.trim());
    }

    private static boolean isMysqlDatetime(String typeName) {
        return typeName != null && "DATETIME".equalsIgnoreCase(typeName.trim());
    }

    private static boolean isPostgresFamily(com.qqmu.jync.model.DatabaseType sourceType) {
        return sourceType != null
                && sourceType.getFamily() == com.qqmu.jync.model.DatabaseType.DialectFamily.POSTGRES;
    }

    private static boolean isPgTextBorneOther(String typeName) {
        if (typeName == null) {
            return false;
        }
        String n = typeName.trim().toLowerCase(java.util.Locale.ROOT);
        return n.equals("json") || n.equals("jsonb") || n.equals("uuid")
                || n.equals("xml");
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
        bind(ps, row, bindOrder, types, null);
    }

    /**
     * As the 4-arg form but knowing the target dialect, so a target whose driver cannot
     * implicitly convert text into a temporal column gets the reader's MySQL wall-clock
     * text parsed back into a {@code java.sql} temporal value first
     * ({@link com.qqmu.jync.service.converter.SqlDialect#requiresTypedTemporalBind()}).
     */
    public static void bind(PreparedStatement ps, Map<String, Object> row, List<String> bindOrder,
                            Map<String, Integer> types,
                            com.qqmu.jync.service.converter.SqlDialect targetDialect)
            throws SQLException {
        bind(ps, row, bindOrder, types, targetDialect, null);
    }

    /**
     * As the 5-arg form, collecting any temporary LOB the target dialect must create so the
     * caller can free it once the batch has executed.
     */
    public static void bind(PreparedStatement ps, Map<String, Object> row, List<String> bindOrder,
                            Map<String, Integer> types,
                            com.qqmu.jync.service.converter.SqlDialect targetDialect,
                            com.qqmu.jync.service.lob.LobBinder.Tracker lobTracker)
            throws SQLException {
        for (int i = 0; i < bindOrder.size(); i++) {
            String column = bindOrder.get(i);
            Object value = row.get(column);
            int idx = i + 1;
            if (value == null) {
                // Some drivers (Oracle in particular) reject setObject(idx, null) without a type.
                Integer type = types.get(column);
                ps.setNull(idx, type == null ? Types.NULL : type);
            } else {
                bindValue(ps, idx, value, types.get(column),
                        targetDialect != null && targetDialect.requiresTypedTemporalBind(),
                        targetDialect, lobTracker);
            }
        }
    }

    private static void bindValue(PreparedStatement ps, int idx, Object value,
                                  Integer targetType, boolean typedTemporal,
                                  com.qqmu.jync.service.converter.SqlDialect targetDialect,
                                  com.qqmu.jync.service.lob.LobBinder.Tracker lobTracker)
            throws SQLException {
        // A value above the target's inline ceiling (Oracle's MERGE binds placeholders as
        // LONGs, capped at 32767 bytes) needs a server-side temporary LOB; an empty string
        // into a CLOB must go the same way, because Oracle binds '' as NULL.
        if (com.qqmu.jync.service.lob.LobBinder.bind(ps, idx, value, targetType,
                targetDialect, lobTracker)) {
            return;
        }
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
            byte[] bytes = (byte[]) value;
            // MySQL BIT(n>1) arrives as a big-endian byte array but is built onto an
            // integer target: decode it as the unsigned integer it represents. BIT(1)
            // may also arrive as a 1-byte array headed for a boolean column. Genuine
            // binary columns (BINARY/VARBINARY/BLOB) keep the raw bytes.
            if (isNumericType(targetType)) {
                ps.setObject(idx, new java.math.BigInteger(1, bytes));
            } else if (targetType != null
                    && (targetType == Types.BIT || targetType == Types.BOOLEAN)
                    && bytes.length == 1) {
                ps.setBoolean(idx, bytes[0] != 0);
            } else {
                ps.setBytes(idx, bytes);
            }
        } else if (value instanceof Boolean && isNumericType(targetType)) {
            // A BIT(1) source value arriving as Boolean must be accepted by an integer
            // target; PostgreSQL-family drivers do not coerce Boolean→INTEGER.
            ps.setObject(idx, (Boolean) value ? 1 : 0);
        } else if (typedTemporal && value instanceof CharSequence
                && targetType != null && CursorTypes.isTemporal(targetType)) {
            // The reader hands MySQL DATETIME/TIME over as the server's wall-clock text
            // (see readRow). MySQL, T-SQL and DB2 convert that literal implicitly and the
            // PostgreSQL family casts it (?::timestamp), but Oracle's VARCHAR2 →
            // DATE/TIMESTAMP conversion runs through the session's NLS format and rejects
            // it — ORA-01843 "not a valid month". Those targets get the text parsed back
            // into a real timestamp here. Text that is not a plain SQL literal — MySQL
            // TIME's -838:59:59, which no java.sql temporal can carry — still binds
            // verbatim, exactly as it did before.
            //
            // The target column must actually BE temporal: a VARCHAR2 target is where the
            // source's own text column lands, and binding it as a Timestamp would make
            // Oracle re-render it through NLS_TIMESTAMP_FORMAT
            // ("2026-10-13 12:34:56" → "13-10月-26 12.34.56.000000 下午"). Only text that
            // merely looks like a timestamp is at risk, and it must survive verbatim.
            Timestamp parsed = timestampFromText((String) value);
            if (parsed == null && targetType == Types.TIME) {
                // A TIME column reaches the binder as the server's literal text on every
                // source (see readValue), and Oracle has no TIME type: structure sync
                // creates it as TIMESTAMP. Bound verbatim, Oracle parses "12:34:56"
                // through NLS_TIMESTAMP_FORMAT and rejects the batch — docker 实测
                // 23ai Free: ORA-01843 "指定的月份无效", the time-of-day sibling of the
                // DATETIME failure above. The parsed value carries 1970-01-01, the same
                // anchor the driver applies to a java.sql.Time bound to Oracle DATE, so
                // native temporal sources land identically.
                parsed = timestampFromTimeOfDay((String) value);
            }
            if (parsed == null) {
                ps.setObject(idx, value);
            } else {
                ps.setTimestamp(idx, parsed);
            }
        } else if (value instanceof CharSequence && isNumericType(targetType)) {
            // Oracle/DM redo text carries every numeric column as a literal string
            // (OracleLiteralConverters keeps the server's text verbatim), so a CDC row
            // arrives here with e.g. "42" for a NUMBER column. Bound as a String, pgjdbc
            // sends VARCHAR(oid type) and PostgreSQL refuses the assignment — "column ...
            // is of type integer but expression is of type character varying" — where a
            // numeric actually binds. Parsing keeps the text's own scale (12.50 stays
            // 12.50) and a literal that is not a plain decimal — BINARY_DOUBLE's
            // "Infinity"/"NaN" — still binds verbatim, exactly as it did before.
            BigDecimal numeric = parseDecimal((String) value);
            if (numeric == null) {
                ps.setObject(idx, value);
            } else {
                ps.setObject(idx, numeric);
            }
        } else {
            ps.setObject(idx, value);
        }
    }

    /** The literal as a {@code BigDecimal}, or null when it is not a plain decimal. */
    private static BigDecimal parseDecimal(String text) {
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The literal as a {@code Timestamp}, or null when it is not "yyyy-MM-dd HH:mm:ss[.f…]". */
    private static Timestamp timestampFromText(String text) {
        String t = text.trim();
        try {
            return Timestamp.valueOf(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Date the driver itself uses when a {@code java.sql.Time} lands on an Oracle DATE. */
    private static final String TIME_OF_DAY_ANCHOR = "1970-01-01 ";

    /**
     * The literal as a {@code Timestamp} on {@value #TIME_OF_DAY_ANCHOR}, or null when it is
     * not a clock time {@code HH:mm:ss[.f…]}. Deliberately strict about the hour of day: the
     * MySQL extended domain (hours past 23, negative values) has no {@code Timestamp} form,
     * and pretending otherwise would write a date the source never meant.
     */
    private static Timestamp timestampFromTimeOfDay(String text) {
        String t = text.trim();
        // Two-digit hours only: Timestamp.valueOf reads fixed offsets, so "9:05:07" would
        // be misparsed rather than rejected.
        if (!t.matches("([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(\\.\\d{1,9})?")) {
            return null;
        }
        try {
            return Timestamp.valueOf(TIME_OF_DAY_ANCHOR + t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isNumericType(Integer jdbcType) {
        if (jdbcType == null) {
            return false;
        }
        switch (jdbcType) {
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE:
            case Types.NUMERIC:
            case Types.DECIMAL:
                return true;
            default:
                return false;
        }
    }

    /** Renders a cursor value as text for durable storage in the progress row. */
    public static String cursorToString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            // SQL Server rowversion / timestamp watermarks arrive as 8-byte arrays.
            // Store the canonical hex text so cursorFromString can rebind the same bytes
            // back into the binary column on the next cycle.
            byte[] bytes = (byte[]) value;
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
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
        // SQL Server rowversion / timestamp watermarks: stored as a hex string, bound back
        // as byte[]. Hex text is 0-9a-f only; reject anything else so a corrupted value
        // fails safe (warn + null) rather than throwing mid-cycle.
        if (jdbcType == Types.BINARY || jdbcType == Types.VARBINARY
                || jdbcType == Types.LONGVARBINARY) {
            String hex = stored.trim();
            if (hex.length() % 2 != 0) {
                log.warn("Unparseable stored binary cursor '{}'; treating as absent", stored);
                return null;
            }
            try {
                byte[] bytes = new byte[hex.length() / 2];
                for (int i = 0; i < bytes.length; i++) {
                    int hi = Character.digit(hex.charAt(i * 2), 16);
                    int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
                    if (hi < 0 || lo < 0) {
                        throw new NumberFormatException("non-hex char");
                    }
                    bytes[i] = (byte) ((hi << 4) | lo);
                }
                return bytes;
            } catch (NumberFormatException e) {
                log.warn("Unparseable stored binary cursor '{}'; treating as absent", stored);
                return null;
            }
        }
        if (CursorTypes.isTemporal(jdbcType)) {
            if (jdbcType == Types.TIME) {
                // TIME columns are stored as the server's literal text (see cursorToString
                // and ruling R5). A TIME cursor legally holds values java.sql.Time cannot
                // carry: hours >= 24 do NOT make Time.valueOf throw — they silently wrap
                // into epoch days (838:59:59 → a Time whose toString is 22:59:59), and
                // negative/fractional literals throw outright. Only a literal that
                // round-trips through Time losslessly keeps the old Time binding; every
                // other legal time literal binds back EXACTLY as text. Genuine garbage is
                // still treated as absent (warn + null), as before.
                String trimmed = stored.trim();
                if (trimmed.matches("-?\\d{1,3}:\\d{2}:\\d{2}(\\.\\d{1,9})?")) {
                    try {
                        java.sql.Time parsed = java.sql.Time.valueOf(trimmed);
                        if (parsed.toString().equals(trimmed)) {
                            return parsed;
                        }
                    } catch (Exception ignored) {
                        // negative or fractional: fall through to the literal
                    }
                    return trimmed;
                }
                log.warn("Unparseable stored time cursor '{}'; treating as absent", stored);
                return null;
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
