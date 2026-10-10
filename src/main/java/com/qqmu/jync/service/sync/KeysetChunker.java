package com.qqmu.jync.service.sync;

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
     *                   first chunk (same order as {@code pkColumns})
     * @param pkUpper    inclusive upper edge of this worker's key range, or null for an
     *                   unbounded (sequential) load; only meaningful on the first key column,
     *                   which is all a range-split load ever has
     * @param strategy   cursor strategy; when it names a column and {@code upperBound} is set,
     *                   the page is also bounded by the watermark exactly like the classic
     *                   full load, so load and stored cursor keep agreeing
     * @param upperBound parsed watermark value, or null when unbounded
     * @param limit      chunk size in rows
     * @param paramsOut  receives the bind values in statement order (appended, not cleared)
     */
    public static String pageSql(SqlDialect dialect, String schema, String table,
                                 List<String> pkColumns, List<Object> lastPk, Object pkUpper,
                                 CursorStrategy strategy, Object upperBound,
                                 int limit, List<Object> paramsOut) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            throw new IllegalArgumentException("Keyset paging requires a primary key");
        }
        if (lastPk != null && lastPk.size() != pkColumns.size()) {
            throw new IllegalArgumentException("Keyset boundary has " + lastPk.size()
                    + " values but the key has " + pkColumns.size() + " columns");
        }

        StringBuilder where = new StringBuilder();
        if (lastPk != null) {
            where.append(keysetWhere(dialect, pkColumns, lastPk, paramsOut));
        }
        if (pkUpper != null) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append('(').append(dialect.quoteIdentifier(pkColumns.get(0))).append(" <= ?)");
            paramsOut.add(pkUpper);
        }
        if (upperBound != null && strategy != null && strategy.getColumn() != null) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            // Same NULL-cursor inclusion as the classic full load: rows with a NULL cursor
            // value fall outside every comparison and would be silently lost otherwise.
            String cursor = dialect.quoteIdentifier(strategy.getColumn());
            where.append('(').append(cursor).append(" <= ? OR ")
                    .append(cursor).append(" IS NULL)");
            paramsOut.add(upperBound);
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
     * Row-comparison as an OR-chain: {@code (a > ?) OR (a = ? AND b > ?) OR (a = ? AND b = ?
     * AND c > ?)}. Parameter order matches the textual order of the placeholders.
     */
    private static String keysetWhere(SqlDialect dialect, List<String> pkColumns,
                                      List<Object> lastPk, List<Object> paramsOut) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkColumns.size(); i++) {
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

    /** Serializes one chunk's boundary (its last row's PK values) for the checkpoint table. */
    public static String encodePk(List<Object> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize keyset boundary", e);
        }
    }

    /** Inverse of {@link #encodePk}; see the note on {@link #JSON} about numeric widths. */
    @SuppressWarnings("unchecked")
    public static List<Object> decodePk(String json) {
        try {
            return JSON.readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse stored keyset boundary: " + json, e);
        }
    }
}
