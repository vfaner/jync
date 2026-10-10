package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
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
                List.of("ID"), List.of(4L), 8L, IDENTITY, null, 50, params);

        // The worker's slice of the key span: (4, 8] — disjoint from its siblings' pages.
        assertThat(sql).isEqualTo("SELECT * FROM `ITEMS`"
                + " WHERE (`ID` > ?) AND (`ID` <= ?) ORDER BY `ID` LIMIT 50 OFFSET 0");
        assertThat(params).containsExactly(4L, 8L);
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
        assertThat(sql).isEqualTo("SELECT * FROM `LEDGER`"
                + " WHERE (`TENANT` > ?) OR (`TENANT` = ? AND `ID` > ?)"
                + " AND (`UPDATED` <= ? OR `UPDATED` IS NULL)"
                + " ORDER BY `TENANT`, `ID` LIMIT 10 OFFSET 0");
        assertThat(params).containsExactly("acme", "acme", 41, upper);
    }

    @Test
    void oraclePagesThroughTheRownumWrapperWithTheKeysetSeekInside() {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new OracleDialect(), "HR", "EMP",
                List.of("EMPNO"), List.of(7), null, IDENTITY, null, 100, params);

        assertThat(sql)
                .startsWith("SELECT * FROM (SELECT a.*, ROWNUM rnum_ FROM (")
                .contains("\"HR\".\"EMP\"")
                .contains("\"EMPNO\" > ?")
                .contains("ORDER BY \"EMPNO\"")
                .endsWith(") a WHERE ROWNUM <= 100) WHERE rnum_ > 0");
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
    void sqlServerGetsBracketQuotingAndOffsetFetchPaging() {
        List<Object> params = new ArrayList<>();
        String sql = KeysetChunker.pageSql(new SqlServerDialect(), "dbo", "ORDERS",
                List.of("ORDER_ID"), List.of(9L), null, IDENTITY, null, 25, params);

        assertThat(sql).isEqualTo("SELECT * FROM [dbo].[ORDERS]"
                + " WHERE ([ORDER_ID] > ?) ORDER BY [ORDER_ID]"
                + " OFFSET 0 ROWS FETCH NEXT 25 ROWS ONLY");
        assertThat(params).containsExactly(9L);
    }

    @Test
    void boundaryValuesSurviveTheJsonRoundTrip() {
        List<Object> pk = List.of(41, "acme", new BigDecimal("19.99"));

        List<Object> back = KeysetChunker.decodePk(KeysetChunker.encodePk(pk));

        assertThat(back).containsExactly(41, "acme", new BigDecimal("19.99"));
    }

    @Test
    void keylessTablesAreRejectedInsteadOfPagingUnbounded() {
        assertThatThrownBy(() -> KeysetChunker.pageSql(new MySqlDialect(), null, "LOGS",
                List.of(), null, null, IDENTITY, null, 50, new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBoundaryWhoseWidthDoesNotMatchTheKeyIsRejected() {
        assertThatThrownBy(() -> KeysetChunker.pageSql(new MySqlDialect(), null, "LEDGER",
                List.of("TENANT", "ID"), List.of("acme"), null, IDENTITY, null, 50,
                new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
