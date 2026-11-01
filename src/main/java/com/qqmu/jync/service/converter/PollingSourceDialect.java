package com.qqmu.jync.service.converter;

import com.qqmu.jync.model.DatabaseType;

/**
 * Read-only dialect for engines that have no log-based CDC and no dialect family of their own
 * (Doris, ClickHouse, Hive, Impala, Elasticsearch SQL, MongoDB Atlas SQL).
 *
 * <p>It inherits the ANSI type mapping and the plain INSERT of {@link GenericSqlDialect} —
 * these engines take part in real-time sync as <em>sources</em> only, so nothing here is
 * expected to reach a write path. It overrides exactly what the read path needs: the
 * identifier quote and the pagination tail.
 *
 * <p>Whether a non-zero offset may be expressed is a constructor flag rather than a per-class
 * decision: Doris and ClickHouse document {@code LIMIT n OFFSET m}; Hive and Impala only do so
 * from server releases this build cannot verify, and the two SQL-only products have no OFFSET
 * at all. Answering {@code false} keeps the keyless full load on the single streamed read
 * instead of failing on page two after page one already committed.
 */
public class PollingSourceDialect extends GenericSqlDialect {

    private final DatabaseType type;
    private final char quote;
    private final boolean offsetPaging;

    public PollingSourceDialect(DatabaseType type, char quote, boolean offsetPaging) {
        this.type = type;
        this.quote = quote;
        this.offsetPaging = offsetPaging;
    }

    @Override
    public DatabaseType explicitType() {
        return type;
    }

    @Override
    protected char quoteChar() {
        return quote;
    }

    @Override
    public boolean supportsOffsetPaging() {
        return offsetPaging;
    }

    @Override
    public String getPaginationSql(String baseSql, long offset, int limit) {
        if (offset != 0L && !offsetPaging) {
            // The caller gates on supportsOffsetPaging(), so this is a guard rather than a
            // reachable code path: emitting it would be a syntax error on the server.
            throw new UnsupportedOperationException(
                    type + " read dialect cannot page by offset");
        }
        return offset == 0L
                ? baseSql + " LIMIT " + limit
                : baseSql + " LIMIT " + limit + " OFFSET " + offset;
    }
}
