package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.SqlServerDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The page SQL is what runs against the customer's source database, so its exact shape is
 * asserted per dialect family: the keyset OR-chain (never OFFSET paging), the watermark bound
 * matching the classic full load, and each product's own LIMIT clause.
 */
class KeysetChunkerTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    @Test
    void firstPageHasNoKeysetPredicateAndCapsWithTheDialectLimit() {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new MySqlDialect(), null, "ITEMS",
                List.of("ID"), null, null, IDENTITY, null, 50, params);

        assertThat(sql).isEqualTo("SELECT * FROM `ITEMS` ORDER BY `ID` LIMIT 50 OFFSET 0");
        assertThat(params).isEmpty();
    }

    @Test
    void aParallelRangePageIsBoundedOnBothKeyEdges() {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new MySqlDialect(), null, "ITEMS",
                List.of("ID"), List.of(4L), List.of(8L), IDENTITY, null, 50, params);

        // The worker's slice of the key span: (4, 8] — disjoint from its siblings' pages.
        assertThat(sql).isEqualTo("SELECT * FROM `ITEMS`"
                + " WHERE (`ID` > ?) AND (`ID` <= ?) ORDER BY `ID` LIMIT 50 OFFSET 0");
        assertThat(params).containsExactly(4L, 8L);
    }

    @Test
    void aSampledUpperBoundExpandsToTheWholeKeyTuple() {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new MySqlDialect(), null, "LEDGER",
                List.of("TENANT", "ID"), List.of("acme"), List.of("globex", 9),
                IDENTITY, null, 10, params);

        // (acme, ...] up to and including (globex, 9).
        assertThat(sql).contains("WHERE (`TENANT` > ?)"
                + " AND ((`TENANT` < ?) OR (`TENANT` = ? AND `ID` <= ?))");
        assertThat(params).containsExactly("acme", "globex", "globex", 9);
    }

    @Test
    void sampledBoundarySqlPicksTheFirstKeyOfEachBucket() {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.sampledBoundarySql(new MySqlDialect(), null, "ITEMS",
                List.of("ID"), IDENTITY, null, 4, params);

        assertThat(sql).contains("NTILE(4) OVER (ORDER BY `ID`) AS jync_bucket");
        assertThat(sql).contains(
                "ROW_NUMBER() OVER (PARTITION BY jync_bucket ORDER BY `ID`) AS jync_rn");
        assertThat(sql).contains("WHERE jync_rn = 1");
        assertThat(sql).contains("ORDER BY `ID`");
        assertThat(params).isEmpty();
    }

    @Test
    void nextPageExpandsACompositeKeyToTheRowComparisonOrChain() {
        CursorStrategy timestamp =
                CursorStrategy.timestamp("UPDATED", Types.TIMESTAMP, "update time");
        Timestamp upper = Timestamp.valueOf("2026-01-01 00:00:00");

        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new MySqlDialect(), null, "LEDGER",
                List.of("TENANT", "ID"), List.of("acme", 41), null, timestamp, upper, 10, params);

        // Row comparison as OR-chain (SQL Server rejects the (a,b) > (?,?) tuple form), plus
        // the same watermark bound — including the NULL-cursor rescue — as the classic load.
        // The chain is parenthesized: without it the following AND would attach to the last
        // term only and the page could read past its upper edge.
        assertThat(sql).isEqualTo("SELECT * FROM `LEDGER`"
                + " WHERE ((`TENANT` > ?) OR (`TENANT` = ? AND `ID` > ?))"
                + " AND (`UPDATED` <= ? OR `UPDATED` IS NULL)"
                + " ORDER BY `TENANT`, `ID` LIMIT 10 OFFSET 0");
        assertThat(params).containsExactly("acme", "acme", 41, upper);
    }

    @Test
    void oraclePagesThroughTheRownumWrapperWithTheKeysetSeekInside() {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new OracleDialect(), "HR", "EMP",
                List.of("EMPNO"), List.of(7), null, IDENTITY, null, 100, params);

        // offset 0 pages cap with plain ROWNUM — no rnum_ helper column, because the copy
        // path derives its INSERT column list from the result metadata and a leaked helper
        // would be written to the target.
        assertThat(sql)
                .startsWith("SELECT * FROM (SELECT * FROM ")
                .contains("\"HR\".\"EMP\"")
                .contains("\"EMPNO\" > ?")
                .contains("ORDER BY \"EMPNO\"")
                .endsWith(") WHERE ROWNUM <= 100")
                .doesNotContain("rnum_");
        assertThat(params).containsExactly(7);
    }

    @Test
    void aRowScnWatermarkIsEmittedUnquotedAndWithoutTheNullRescue() {
        CursorStrategy rowScn = CursorStrategy.rowScn("row-level SCN");
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new OracleDialect(), "HR", "EMP",
                List.of("EMPNO"), List.of(7), null, rowScn, 1234567L, 100, params);

        // ORA_ROWSCN is a pseudo-column: quoting it is a syntax error on Oracle, and it is
        // never NULL, so the NULL-cursor rescue of a real column must not appear.
        assertThat(sql).contains("(ORA_ROWSCN <= ?)");
        assertThat(sql).doesNotContain("\"ORA_ROWSCN\"");
        assertThat(sql).doesNotContain("IS NULL");
        assertThat(params).containsExactly(7, 1234567L);
    }

    @Test
    void sqlServerGetsBracketQuotingAndTopPaging() {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new SqlServerDialect(), "dbo", "ORDERS",
                List.of("ORDER_ID"), List.of(9L), null, IDENTITY, null, 25, params);

        // TOP, not OFFSET/FETCH: the latter is 2012+ and made every chunk of a 2008/R2
        // full load a syntax error. The keyset predicate already did the seeking.
        assertThat(sql).isEqualTo("SELECT TOP (25) * FROM [dbo].[ORDERS]"
                + " WHERE ([ORDER_ID] > ?) ORDER BY [ORDER_ID]");
        assertThat(params).containsExactly(9L);
    }

    @Test
    void boundaryValuesSurviveTheJsonRoundTrip() {
        List<Object> pk = List.of(41, "acme", new BigDecimal("19.99"));

        List<Object> back = KeysetChunker.decodePk(KeysetChunker.encodePk(pk));

        assertThat(back).containsExactly(41, "acme", new BigDecimal("19.99"));
    }

    @Test
    void temporalBoundariesComeBackAsSqlTemporalValuesWhenTheKeyTypeSaysSo() {
        Timestamp timestamp = Timestamp.valueOf("2026-10-20 12:34:56.789");
        java.sql.Date date = java.sql.Date.valueOf("2026-10-20");

        // A checkpoint stores the boundary as JSON; a temporal key serializes as epoch millis.
        // Rebinding a Long to a TIMESTAMP/DATE column fails on Oracle and PostgreSQL and can
        // silently compare the wrong domain elsewhere, so the resume path must restore the
        // column's type before the value reaches the keyset predicate.
        assertThat(KeysetChunker.decodePk(
                KeysetChunker.encodePk(List.of(timestamp)), List.of(Types.TIMESTAMP)))
                .containsExactly(timestamp);
        assertThat(KeysetChunker.decodePk(
                KeysetChunker.encodePk(List.of(date)), List.of(Types.DATE)))
                .containsExactly(date);
    }

    @Test
    void anOffsetDateTimeBoundaryIsEncodedInsteadOfFailingTheWholeLoad() {
        // JdbcValueNormalizer hands a timestamptz (PostgreSQL) / datetimeoffset (SQL Server)
        // key back as java.time.OffsetDateTime. The checkpoint mapper has no JavaTime module,
        // so serializing that value threw InvalidDefinitionException — encodePk wrapped it as
        // "Cannot serialize keyset boundary" and the chunked full load of every such table
        // failed on every cycle, before its first chunk was ever checkpointed.
        OffsetDateTime key = OffsetDateTime.of(2026, 10, 20, 12, 34, 56, 789_000_000,
                ZoneOffset.ofHours(8));

        String json = KeysetChunker.encodePk(List.of(key));

        assertThat(KeysetChunker.decodePk(json, List.of(Types.TIMESTAMP_WITH_TIMEZONE)))
                .containsExactly(Timestamp.from(key.toInstant()));
    }

    @Test
    void aLiteralNullCheckpointMeansNoBoundaryInsteadOfThrowing() {
        // encodePk(null) 写的是字面量 "null"(「本页没有边界值」)。resume 时必须还原成
        // 「无边界」并从头读该范围,而不是在 values.size() 上 NPE 掉整个断点续传。
        assertThat(KeysetChunker.decodePk(KeysetChunker.encodePk(null),
                List.of(Types.TIMESTAMP))).isNull();
    }

    @Test
    void subMillisecondTemporalBoundariesKeepTheirNanosAcrossTheJsonRoundTrip() {
        // Oracle TIMESTAMP / PG timestamptz / SQL Server datetime2 的键有微秒甚至 100ns
        // 精度。JSON 只存 epoch millis 会把边界向下截断,重启续传时把已同步的行再读一遍;
        // 解回时必须保住亚毫秒部分。
        Timestamp boundary = Timestamp.valueOf("2026-10-20 12:34:56.789123");

        assertThat(KeysetChunker.decodePk(KeysetChunker.encodePk(List.of(boundary)),
                List.of(Types.TIMESTAMP))).containsExactly(boundary);
    }

    @Test
    void binaryBoundariesComeBackAsBytesSoAResumedPageSeeksWithTheRealValue() {
        List<Object> pk = List.of(new byte[] {1, 2, 3}, "tail");

        List<Object> back = KeysetChunker.decodePk(KeysetChunker.encodePk(pk),
                List.of(Types.VARBINARY, Types.VARCHAR));

        // JSON has no binary type: a byte[] key is stored as Base64 text. Rebinding that text
        // against a BINARY/VARBINARY key compares the characters of the encoding with the key's
        // bytes — every key below it is skipped — so the resume must restore the bytes.
        assertThat(back.get(0)).isEqualTo(new byte[] {1, 2, 3});
        assertThat(back.get(1)).isEqualTo("tail");
    }

    @Test
    void keylessTablesAreRejectedInsteadOfPagingUnbounded() {
        assertThatThrownBy(() -> KeysetChunker.pageSql(new MySqlDialect(), null, "LOGS",
                List.of(), null, null, IDENTITY, null, 50, new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPrefixBoundaryBoundsTheLeadingColumnAndOverWideBoundariesAreRejected() {
        // The first chunk of a composite key's range starts from the leading column's range
        // edge, not from a full key tuple.
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new MySqlDialect(), null, "LEDGER",
                List.of("TENANT", "ID"), List.of("acme"), null, IDENTITY, null, 50, params);

        assertThat(sql).contains("`TENANT` > ?");
        assertThat(sql).doesNotContain("`ID` > ?");
        assertThat(params).containsExactly("acme");

        assertThatThrownBy(() -> KeysetChunker.pageSql(new MySqlDialect(), null, "LEDGER",
                List.of("TENANT", "ID"), List.of("acme", 7, "extra"), null, IDENTITY, null, 50,
                new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeylessPageProjectsEveryColumnAndOrdersByTheOrderableOnes() {
        // The projection must stay every column (the copy needs them all); ORDER BY only the
        // orderable subset, because a LOB column is not orderable on every product.
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.keylessPageSql(new MySqlDialect(), null, "EVENTS",
                List.of("SEQ", "NAME", "BODY"), List.of("SEQ", "NAME"),
                CursorStrategy.none("full load only"), null, 3, 2, params);

        assertThat(sql).isEqualTo("SELECT `SEQ`, `NAME`, `BODY` FROM `EVENTS`"
                + " ORDER BY `SEQ`, `NAME` LIMIT 2 OFFSET 3");
        assertThat(params).isEmpty();
    }

    @Test
    void aKeylessOraclePageKeepsRnumOutOfTheProjection() {
        // Oracle's offset wrapper needs a ROWNUM helper; an outer SELECT * would surface it in
        // the result metadata and every page would fail on an INSERT column that does not exist.
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.keylessPageSql(new OracleDialect(), "APP", "EVENTS",
                List.of("SEQ", "NAME"), List.of("SEQ"), IDENTITY, new Timestamp(5L), 3, 2, params);

        assertThat(sql).startsWith(
                "SELECT \"SEQ\", \"NAME\" FROM (SELECT a.*, ROWNUM rnum_ FROM (");
        assertThat(sql).contains("WHERE (\"ID\" <= ? OR \"ID\" IS NULL)");
        assertThat(sql).contains("ORDER BY \"SEQ\"");
        assertThat(sql).endsWith(") WHERE rnum_ > 3");
        assertThat(params).containsExactly(new Timestamp(5L));
    }

    @Test
    void theFirstKeylessOraclePageHasNoHelperColumnAtAll() {
        String sql = KeysetChunker.keylessPageSql(new OracleDialect(), "APP", "EVENTS",
                List.of("SEQ", "NAME"), List.of("SEQ"),
                CursorStrategy.none("full load only"), null, 0, 2, new ArrayList<>());

        assertThat(sql).doesNotContain("rnum_");
        assertThat(sql).startsWith("SELECT * FROM (SELECT \"SEQ\", \"NAME\" FROM \"APP\".\"EVENTS\"");
    }

    // --- drift walk primitives -------------------------------------------------------

    private static final CursorStrategy BY_UPDATED =
            CursorStrategy.timestamp("UPDATED", Types.TIMESTAMP, "update time");

    @Test
    void keyPageSqlProjectsOnlyTheKeysSoADriftWalkNeverReadsRowBodies() {
        // A drift walk only needs to know WHICH keys exist; SELECT * would drag every LOB of
        // the table across the wire for a comparison that never looks at a single value.
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.keyPageSql(new MySqlDialect(), null, "ITEMS",
                List.of("ID"), List.of(4L), List.of(8L), BY_UPDATED, new Timestamp(5L),
                50, params);

        assertThat(sql).isEqualTo("SELECT `ID` FROM `ITEMS`"
                + " WHERE (`ID` > ?) AND (`ID` <= ?)"
                + " AND (`UPDATED` <= ? OR `UPDATED` IS NULL)"
                + " ORDER BY `ID` LIMIT 50 OFFSET 0");
        assertThat(params).containsExactly(4L, 8L, new Timestamp(5L));
    }

    @Test
    void rangeCountSqlCountsInsideAKeyRangeWithTheAuditsWatermark() {
        // The per-range count is the audit's COUNT(*) narrowed to (lo, hi]; it must carry the
        // very same watermark predicate, or the two sides would count different row sets.
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.rangeCountSql(new MySqlDialect(), null, "ITEMS",
                List.of("ID"), List.of(4L), List.of(8L), BY_UPDATED, new Timestamp(5L), params);

        assertThat(sql).isEqualTo("SELECT COUNT(*) FROM `ITEMS`"
                + " WHERE (`ID` > ?) AND (`ID` <= ?)"
                + " AND (`UPDATED` <= ? OR `UPDATED` IS NULL)");
        assertThat(params).containsExactly(4L, 8L, new Timestamp(5L));
    }

    @Test
    void anUnboundedRangeCountOnlyCarriesTheWatermark() {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.rangeCountSql(new MySqlDialect(), null, "ITEMS",
                List.of("ID"), null, null, BY_UPDATED, new Timestamp(5L), params);

        assertThat(sql).isEqualTo("SELECT COUNT(*) FROM `ITEMS`"
                + " WHERE (`UPDATED` <= ? OR `UPDATED` IS NULL)");
        assertThat(params).containsExactly(new Timestamp(5L));
    }

    @Test
    void keysSelectSqlFetchesExactlyTheMissingKeysAndNothingElse() {
        // One statement per batch of missing keys: IN for a single-column key, bounded by the
        // same watermark, so a row that moved past the claimed cursor since the audit is left
        // to the normal incremental window.
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.keysSelectSql(new MySqlDialect(), null, "ITEMS",
                List.of("ID"), List.of(List.of(4L), List.of(9L)), BY_UPDATED,
                new Timestamp(5L), params);

        assertThat(sql).isEqualTo("SELECT * FROM `ITEMS`"
                + " WHERE (`ID` IN (?, ?))"
                + " AND (`UPDATED` <= ? OR `UPDATED` IS NULL)");
        assertThat(params).containsExactly(4L, 9L, new Timestamp(5L));
    }

    @Test
    void aCompositeMissingKeySetUsesTheRowEqualityOrChain() {
        // Composite keys have no IN form that every product accepts for a tuple, so the same
        // OR-chain the pager uses for row comparison is used for equality.
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.keysSelectSql(new MySqlDialect(), null, "LEDGER",
                List.of("TENANT", "ID"),
                List.of(List.of("acme", 1L), List.of("globex", 2L)), BY_UPDATED,
                new Timestamp(5L), params);

        assertThat(sql).isEqualTo("SELECT * FROM `LEDGER`"
                + " WHERE ((`TENANT` = ? AND `ID` = ?) OR (`TENANT` = ? AND `ID` = ?))"
                + " AND (`UPDATED` <= ? OR `UPDATED` IS NULL)");
        assertThat(params).containsExactly("acme", 1L, "globex", 2L, new Timestamp(5L));
    }
}
