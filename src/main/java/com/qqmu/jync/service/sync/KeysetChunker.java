package com.qqmu.jync.service.sync;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * Builds the bounded keyset queries that let a huge initial full load run as a chain of short,
 * checkpointable reads instead of one table-long stream.
 *
 * <p>Paging is by key boundary, never by OFFSET: {@code WHERE (pk) > (last boundary) ORDER BY pk
 * LIMIT n} stays index-only on every supported dialect, while an OFFSET page walk gets linearly
 * slower the deeper it goes. The boundary values of the last row of a completed chunk are what
 * {@code ChunkCheckpoint} persists, so the same expression also implements resume.
 *
 * <p>Composite keys expand to the standard OR-chain form of row comparison — some supported
 * products (notably SQL Server) do not accept the tuple syntax {@code (a, b) > (?, ?)} — and the
 * row-wise comparison is done entirely with bind parameters, so no literal escaping is involved.
 */
public final class KeysetChunker {

    /**
     * PK boundary values travel through JSON (they are Numbers, Strings, or temporal wrappers).
     * USE_BIG_DECIMAL_FOR_FLOATS keeps decimals exact; note that integral values may come back
     * narrower than they went in (a Long 5 decodes as Integer 5) — harmless, because the decoded
     * value is only ever re-bound as a {@code >} / {@code =} comparison parameter, and SQL
     * numeric comparison widens.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private KeysetChunker() {
    }

    /**
     * One page of a keyset-chunked load.
     *
     * @param dialect    source dialect, for quoting and the LIMIT clause
     * @param schema     source schema (may be null)
     * @param table      source table name
     * @param pkColumns  primary-key columns in key order; must not be empty
     * @param lastPk     boundary values of the previous chunk's last row, or null for the
     *                   first chunk (same order as {@code pkColumns}, possibly a prefix)
     * @param upperKey   inclusive upper edge of this worker's key range as a full or prefix
     *                   key tuple, or null for an unbounded (sequential) load. A one-element
     *                   tuple is a numeric/leading-column edge; the full tuple is what a
     *                   sampled range boundary records
     * @param strategy   cursor strategy; when it names a column and {@code upperBound} is set,
     *                   the page is also bounded by the watermark exactly like the classic
     *                   full load, so load and stored cursor keep agreeing
     * @param upperBound parsed watermark value, or null when unbounded
     * @param limit      chunk size in rows
     * @param paramsOut  receives the bind values in statement order (appended, not cleared)
     */
    public static String pageSql(SqlDialect dialect, String schema, String table,
                                 List<String> pkColumns, List<Object> lastPk,
                                 List<Object> upperKey,
                                 CursorStrategy strategy, Object upperBound,
                                 int limit, List<Object> paramsOut) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            throw new IllegalArgumentException("Keyset paging requires a primary key");
        }
        // A shorter boundary is a prefix: "leading column > lo" is exactly the lower edge of
        // a range split on that column, and it is what a composite key's first chunk starts
        // from (there is no earlier full tuple to compare against).
        if (lastPk != null && (lastPk.isEmpty() || lastPk.size() > pkColumns.size())) {
            throw new IllegalArgumentException("Keyset boundary has " + lastPk.size()
                    + " values but the key has " + pkColumns.size() + " columns");
        }
        if (upperKey != null && (upperKey.isEmpty() || upperKey.size() > pkColumns.size())) {
            throw new IllegalArgumentException("Keyset upper edge has " + upperKey.size()
                    + " values but the key has " + pkColumns.size() + " columns");
        }

        StringBuilder where = new StringBuilder();
        if (lastPk != null) {
            // The lower edge is an OR-chain; without its own parentheses the following
            // "AND upper-edge" would bind only to the chain's last term, letting a page read
            // past its range whenever the leading column alone matched. A single term is
            // already parenthesized by the builder itself.
            String lower = keysetWhere(dialect, pkColumns, lastPk, paramsOut);
            where.append(lastPk.size() > 1 ? "(" + lower + ")" : lower);
        }
        if (upperKey != null) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            String upper = upperWhere(dialect, pkColumns, upperKey, paramsOut);
            where.append(upperKey.size() > 1 ? "(" + upper + ")" : upper);
        }
        if (upperBound != null && strategy != null && strategy.getColumn() != null) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append('(')
                    .append(cursorPredicate(dialect, strategy, upperBound, paramsOut))
                    .append(')');
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(dialect.qualify(schema, table));
        if (where.length() > 0) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" ORDER BY ");
        for (int i = 0; i < pkColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(dialect.quoteIdentifier(pkColumns.get(i)));
        }
        // offset 0: the keyset predicate already did the seeking, LIMIT only caps the page.
        return dialect.getPaginationSql(sql.toString(), 0, limit);
    }

    /**
     * One page of a keyless table's full load.
     *
     * <p>There is no key to seek with, so the page walk uses OFFSET — the one shape every
     * supported product accepts — over a deterministic total order given by the table's own
     * orderable columns. The projection is explicit rather than {@code SELECT *} so that
     * pagination wrappers which add helper columns (Oracle's ROWNUM) cannot leak one into the
     * copied row shape. Callers must only use this walk on a target they left empty themselves:
     * an offset says nothing about <em>which</em> rows are already there.
     *
     * @param columns      all table columns, in table order (the projection)
     * @param orderColumns the orderable subset of {@code columns}, used for ORDER BY
     * @param offset       rows already copied by this load
     */
    public static String keylessPageSql(SqlDialect dialect, String schema, String table,
                                        List<String> columns, List<String> orderColumns,
                                        CursorStrategy strategy, Object upperBound,
                                        long offset, int limit, List<Object> paramsOut) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Keyless paging requires at least one column");
        }
        if (orderColumns == null || orderColumns.isEmpty()) {
            throw new IllegalArgumentException("Keyless paging requires an orderable column");
        }
        List<String> projected = new ArrayList<>(columns.size());
        for (String column : columns) {
            projected.add(dialect.quoteIdentifier(column));
        }
        List<String> ordered = new ArrayList<>(orderColumns.size());
        for (String column : orderColumns) {
            ordered.add(dialect.quoteIdentifier(column));
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", projected))
                .append(" FROM ").append(dialect.qualify(schema, table));
        if (upperBound != null && strategy != null && strategy.getColumn() != null) {
            sql.append(" WHERE (")
                    .append(cursorPredicate(dialect, strategy, upperBound, paramsOut))
                    .append(')');
        }
        sql.append(" ORDER BY ").append(String.join(", ", ordered));
        return dialect.getProjectedPaginationSql(sql.toString(), projected, offset, limit);
    }

    /**
     * One page of <em>keys only</em> — the projection of the drift walk.
     *
     * <p>The row-count repair compares which keys each side holds, never what a row contains;
     * projecting the key columns keeps the walk index-only even on a table whose rows carry
     * LOBs that {@code SELECT *} would spill across the wire for nothing.
     *
     * @param lastPk   exclusive lower boundary, or null for the first page of the range
     * @param upperKey inclusive upper edge of the range, or null for unbounded
     */
    public static String keyPageSql(SqlDialect dialect, String schema, String table,
                                    List<String> pkColumns, List<Object> lastPk,
                                    List<Object> upperKey, CursorStrategy strategy,
                                    Object upperBound, int limit, List<Object> paramsOut) {
        String where = rangeWhere(dialect, pkColumns, lastPk, upperKey, strategy, upperBound,
                paramsOut);
        List<String> projected = new ArrayList<>(pkColumns.size());
        for (String column : pkColumns) {
            projected.add(dialect.quoteIdentifier(column));
        }
        String projection = String.join(", ", projected);
        StringBuilder sql = new StringBuilder("SELECT ").append(projection)
                .append(" FROM ").append(dialect.qualify(schema, table));
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" ORDER BY ").append(projection);
        // The projection is repeated in the pagination wrapper: Oracle's ROWNUM form adds a
        // helper column that an outer SELECT * would surface as a key column.
        return dialect.getProjectedPaginationSql(sql.toString(), projected, 0, limit);
    }

    /**
     * {@code COUNT(*)} of the rows inside a key range and at or below the watermark — the
     * audit's per-side count narrowed to one range of the drift walk.
     *
     * <p>Both sides of the walk are counted with this one statement shape, so the two numbers
     * describe the same set of rows, which is the only thing that makes them comparable.
     */
    public static String rangeCountSql(SqlDialect dialect, String schema, String table,
                                       List<String> pkColumns, List<Object> lowerExclusive,
                                       List<Object> upperInclusive, CursorStrategy strategy,
                                       Object upperBound, List<Object> paramsOut) {
        String where = rangeWhere(dialect, pkColumns, lowerExclusive, upperInclusive, strategy,
                upperBound, paramsOut);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ")
                .append(dialect.qualify(schema, table));
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        return sql.toString();
    }

    /**
     * The full rows for a known set of keys — how a repair fetches only the rows it proved
     * missing. A single-column key uses {@code IN}; a composite key uses the same row-equality
     * OR-chain as the pager, because a tuple {@code IN} is not accepted everywhere.
     *
     * <p>Callers batch the key list: Oracle rejects an {@code IN} list longer than 1000
     * expressions, and every product is happier with a short statement.
     */
    public static String keysSelectSql(SqlDialect dialect, String schema, String table,
                                       List<String> pkColumns, List<List<Object>> keys,
                                       CursorStrategy strategy, Object upperBound,
                                       List<Object> paramsOut) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("A key fetch needs at least one key");
        }
        StringBuilder match = new StringBuilder();
        if (pkColumns.size() == 1) {
            match.append('(').append(dialect.quoteIdentifier(pkColumns.get(0))).append(" IN (");
            for (int i = 0; i < keys.size(); i++) {
                match.append(i > 0 ? ", ?" : "?");
                paramsOut.add(keys.get(i).get(0));
            }
            match.append("))");
        } else {
            // The whole OR-chain stays parenthesized: the following AND (the watermark) must
            // bind to the chain, not to its last term.
            match.append('(');
            for (int k = 0; k < keys.size(); k++) {
                List<Object> key = keys.get(k);
                if (key.size() != pkColumns.size()) {
                    throw new IllegalArgumentException("Key has " + key.size()
                            + " values but the key has " + pkColumns.size() + " columns");
                }
                if (k > 0) {
                    match.append(" OR ");
                }
                match.append('(');
                for (int i = 0; i < key.size(); i++) {
                    if (i > 0) {
                        match.append(" AND ");
                    }
                    match.append(dialect.quoteIdentifier(pkColumns.get(i))).append(" = ?");
                    paramsOut.add(key.get(i));
                }
                match.append(')');
            }
            match.append(')');
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(dialect.qualify(schema, table)).append(" WHERE ").append(match);
        appendCursorBound(sql, dialect, strategy, upperBound, paramsOut);
        return sql.toString();
    }

    /** The key-range predicate plus the watermark, shared by the count and the key page. */
    private static String rangeWhere(SqlDialect dialect, List<String> pkColumns,
                                     List<Object> lowerExclusive, List<Object> upperInclusive,
                                     CursorStrategy strategy, Object upperBound,
                                     List<Object> paramsOut) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            throw new IllegalArgumentException("Key-range queries require a primary key");
        }
        StringBuilder where = new StringBuilder();
        if (lowerExclusive != null) {
            String lower = keysetWhere(dialect, pkColumns, lowerExclusive, paramsOut);
            where.append(lowerExclusive.size() > 1 ? "(" + lower + ")" : lower);
        }
        if (upperInclusive != null) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            String upper = upperWhere(dialect, pkColumns, upperInclusive, paramsOut);
            where.append(upperInclusive.size() > 1 ? "(" + upper + ")" : upper);
        }
        appendCursorBound(where, dialect, strategy, upperBound, paramsOut);
        return where.toString();
    }

    /** Appends the watermark predicate in place when one applies; a no-op otherwise. */
    private static void appendCursorBound(StringBuilder where, SqlDialect dialect,
                                          CursorStrategy strategy, Object upperBound,
                                          List<Object> paramsOut) {
        if (upperBound == null || strategy == null || strategy.getColumn() == null) {
            return;
        }
        if (where.length() > 0) {
            where.append(" AND ");
        }
        where.append('(')
                .append(cursorPredicate(dialect, strategy, upperBound, paramsOut))
                .append(')');
    }

    /**
     * The watermark bound shared by every paged read: the cursor column at or below the
     * captured watermark, plus rows whose cursor is NULL (they fall outside every comparison
     * and would be silently lost). A pseudo-column such as {@code ORA_ROWSCN} is never NULL, so
     * the inclusion would be dead weight — and quoting the pseudo-column is a syntax error.
     */
    private static String cursorPredicate(SqlDialect dialect, CursorStrategy strategy,
                                          Object upperBound, List<Object> paramsOut) {
        String cursor = sqlRef(dialect, strategy);
        paramsOut.add(upperBound);
        return strategy.isPseudoColumn()
                ? cursor + " <= ?"
                : cursor + " <= ? OR " + cursor + " IS NULL";
    }

    /**
     * Row-comparison as an OR-chain: {@code (a > ?) OR (a = ? AND b > ?) OR (a = ? AND b = ?
     * AND c > ?)}. Parameter order matches the textual order of the placeholders.
     */
    private static String keysetWhere(SqlDialect dialect, List<String> pkColumns,
                                      List<Object> lastPk, List<Object> paramsOut) {
        StringBuilder sb = new StringBuilder();
        // A prefix boundary stops at its own last column; the columns it does not name are
        // free, which is what makes "leading column > lo" a correct range edge.
        for (int i = 0; i < lastPk.size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            sb.append('(');
            for (int j = 0; j < i; j++) {
                sb.append(dialect.quoteIdentifier(pkColumns.get(j))).append(" = ? AND ");
                paramsOut.add(lastPk.get(j));
            }
            sb.append(dialect.quoteIdentifier(pkColumns.get(i))).append(" > ?)");
            paramsOut.add(lastPk.get(i));
        }
        return sb.toString();
    }

    /**
     * Inclusive upper edge as the mirror-image OR-chain: {@code (a < ?) OR (a = ? AND b < ?)
     * OR ... OR (a = ? AND ... AND z <= ?)}. A one-element tuple gives the plain
     * {@code a <= ?} used by numeric range splits.
     */
    private static String upperWhere(SqlDialect dialect, List<String> pkColumns,
                                     List<Object> upperKey, List<Object> paramsOut) {
        StringBuilder sb = new StringBuilder();
        boolean last = false;
        for (int i = 0; i < upperKey.size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            last = i == upperKey.size() - 1;
            sb.append('(');
            for (int j = 0; j < i; j++) {
                sb.append(dialect.quoteIdentifier(pkColumns.get(j))).append(" = ? AND ");
                paramsOut.add(upperKey.get(j));
            }
            sb.append(dialect.quoteIdentifier(pkColumns.get(i)))
                    .append(last ? " <= ?)" : " < ?)");
            paramsOut.add(upperKey.get(i));
        }
        return sb.toString();
    }

    /**
     * One query returning the <em>first key of every bucket</em> of the ordered, watermark-
     * bounded key set, which is how a table without an integral leading key gets range
     * boundaries at all: the ordinal key span cannot be cut arithmetically, but the source
     * itself can hand back roughly evenly spaced keys.
     *
     * <p>Two window levels are needed: {@code NTILE} labels the buckets, and an outer
     * {@code ROW_NUMBER PARTITION BY} keeps only the first row of each. The first row (the
     * global minimum) is not needed as an edge because the first range is unbounded below;
     * the remaining rows are inclusive upper edges, and each row is also the lower edge of
     * the range spanning to the next one. The final range is unbounded above, so no MAX
     * query is needed. Requires window functions; the caller falls back to the sequential
     * load when the product rejects the statement.
     *
     * @param buckets number of NTILE buckets; one bucket per range, because every bucket
     *                contributes exactly one range
     */
    public static String sampledBoundarySql(SqlDialect dialect, String schema, String table,
                                            List<String> pkColumns, CursorStrategy strategy,
                                            Object upperBound, int buckets,
                                            List<Object> paramsOut) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            throw new IllegalArgumentException("Boundary sampling requires a primary key");
        }
        if (buckets < 2) {
            throw new IllegalArgumentException("Boundary sampling needs at least 2 buckets");
        }
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < pkColumns.size(); i++) {
            if (i > 0) {
                columns.append(", ");
            }
            columns.append(dialect.quoteIdentifier(pkColumns.get(i)));
        }
        String order = columns.toString();

        StringBuilder inner = new StringBuilder("SELECT ").append(columns)
                .append(", NTILE(").append(buckets).append(") OVER (ORDER BY ").append(order)
                .append(") AS jync_bucket FROM ")
                .append(dialect.qualify(schema, table));
        if (upperBound != null && strategy != null && strategy.getColumn() != null) {
            String cursor = sqlRef(dialect, strategy);
            inner.append(" WHERE (").append(cursor).append(" <= ?");
            if (!strategy.isPseudoColumn()) {
                inner.append(" OR ").append(cursor).append(" IS NULL");
            }
            inner.append(')');
            paramsOut.add(upperBound);
        }

        return "SELECT " + columns
                + " FROM (SELECT " + columns + ", jync_bucket,"
                + " ROW_NUMBER() OVER (PARTITION BY jync_bucket ORDER BY " + order
                + ") AS jync_rn FROM (" + inner + ") jync_buckets) jync_first_rows"
                + " WHERE jync_rn = 1 ORDER BY " + order;
    }

    /**
     * SQL reference to a strategy's cursor: the quoted identifier for a real column, the
     * bare name for a pseudo-column like {@code ORA_ROWSCN} (quoting a pseudo-column is a
     * syntax error on Oracle).
     */
    public static String sqlRef(SqlDialect dialect, CursorStrategy strategy) {
        return strategy.isPseudoColumn()
                ? strategy.getColumn()
                : dialect.quoteIdentifier(strategy.getColumn());
    }

    /** Serializes one chunk's boundary (its last row's PK values) for the checkpoint table. */
    public static String encodePk(List<Object> values) {
        try {
            return JSON.writeValueAsString(values == null ? null : portableValues(values));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize keyset boundary", e);
        }
    }

    /**
     * Maps {@code java.time} boundary values onto the {@code java.sql} types this format
     * already round-trips. {@link JdbcValueNormalizer} reads a PostgreSQL {@code timestamptz}
     * or SQL Server {@code datetimeoffset} key as an {@link java.time.OffsetDateTime}, and the
     * mapper deliberately has no JavaTime module — the stored form is epoch millis, which
     * {@link #decodePk} restores using the key column's JDBC type. Without this conversion
     * the write failed with Jackson's InvalidDefinitionException, surfaced as "Cannot
     * serialize keyset boundary", so the chunked full load of any such table failed on every
     * cycle before its first chunk was checkpointed.
     */
    private static List<Object> portableValues(List<Object> values) {
        List<Object> portable = new ArrayList<>(values.size());
        for (Object value : values) {
            portable.add(portableValue(value));
        }
        return portable;
    }

    private static Object portableValue(Object value) {
        if (value instanceof java.time.OffsetDateTime) {
            return java.sql.Timestamp.from(((java.time.OffsetDateTime) value).toInstant());
        }
        if (value instanceof java.time.ZonedDateTime) {
            return java.sql.Timestamp.from(((java.time.ZonedDateTime) value).toInstant());
        }
        if (value instanceof java.time.Instant) {
            return java.sql.Timestamp.from((java.time.Instant) value);
        }
        if (value instanceof java.time.LocalDateTime) {
            return java.sql.Timestamp.valueOf((java.time.LocalDateTime) value);
        }
        if (value instanceof java.time.LocalDate) {
            return java.sql.Date.valueOf((java.time.LocalDate) value);
        }
        return value;
    }

    /** Inverse of {@link #encodePk}; see the note on {@link #JSON} about numeric widths. */
    public static List<Object> decodePk(String json) {
        return decodePk(json, null);
    }

    /**
     * Inverse of {@link #encodePk}, restoring temporal and binary key values to the JDBC type
     * the source metadata reports.
     *
     * <p>JSON has no date or binary type: a {@link java.sql.Timestamp} boundary is written as
     * epoch milliseconds and comes back as a number, and a {@code byte[]} boundary is written
     * as Base64 text and comes back as a string. Rebinding either to its column's type is
     * wrong: a TIMESTAMP/DATE column rejects the number on Oracle and PostgreSQL and compares
     * the wrong domain on other products, while a BINARY/VARBINARY column compares the
     * characters of the Base64 encoding with the key's bytes (every key below that text is
     * skipped) or refuses the comparison outright. The caller passes the PK columns' JDBC
     * types; a null or shorter type list leaves those positions untouched.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> decodePk(String json, List<Integer> pkJdbcTypes) {
        List<Object> values;
        try {
            values = JSON.readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse stored keyset boundary: " + json, e);
        }
        if (pkJdbcTypes == null) {
            return values;
        }
        for (int i = 0; i < values.size() && i < pkJdbcTypes.size(); i++) {
            Object value = values.get(i);
            Integer type = pkJdbcTypes.get(i);
            if (type == null) {
                continue;
            }
            if (value instanceof String && isBinaryType(type)) {
                try {
                    values.set(i, java.util.Base64.getDecoder().decode((String) value));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                            "Cannot restore the binary keyset boundary: " + json, e);
                }
                continue;
            }
            if (!(value instanceof Number)) {
                continue;
            }
            long millis = ((Number) value).longValue();
            if (type == java.sql.Types.TIMESTAMP
                    || type == java.sql.Types.TIMESTAMP_WITH_TIMEZONE) {
                values.set(i, new java.sql.Timestamp(millis));
            } else if (type == java.sql.Types.DATE) {
                values.set(i, new java.sql.Date(millis));
            }
        }
        return values;
    }

    /** The JDBC types Jackson writes as Base64 text and a key column can carry. */
    private static boolean isBinaryType(int type) {
        return type == java.sql.Types.BINARY
                || type == java.sql.Types.VARBINARY
                || type == java.sql.Types.LONGVARBINARY
                || type == java.sql.Types.BLOB;
    }
}
