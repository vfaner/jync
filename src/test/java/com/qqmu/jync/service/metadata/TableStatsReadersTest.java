package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Catalog statistics for the bulk-plan page.
 *
 * <p>The bulk planner needs rows and bytes for every table in one pass. It must not reuse
 * {@link MetadataReader#estimateRowCount}: that method falls back to {@code COUNT(*)} when the
 * dictionary has no statistics, which is right for the sync engine's strategy choice and
 * catastrophic for a 2000-table listing. This contract says so out loud — a table with no
 * statistics comes back with a null field, and a broken query comes back as an empty map, never
 * as a silent scan.
 *
 * <p>All five product readers expose the same three labels ({@code TABLE_NAME},
 * {@code ROW_EST}, {@code TOTAL_BYTES}) so the parsing is shared; these tests pin that
 * contract and the "unknown" rules: null, zero and negative all mean unknown.
 */
class TableStatsReadersTest {

    private record Row(String name, Long rows, Long bytes) {
    }

    private static Connection connectionWithRows(Row... rows) throws SQLException {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);

        // -1 so the first next() lands on index 0: the row accessors below read the cursor
        // as "the row just returned", not "the row after it".
        AtomicInteger cursor = new AtomicInteger(-1);
        AtomicBoolean lastWasNull = new AtomicBoolean();
        when(rs.next()).thenAnswer(invocation -> cursor.incrementAndGet() < rows.length);
        when(rs.getString("TABLE_NAME"))
                .thenAnswer(invocation -> rows[cursor.get()].name());
        when(rs.getLong("ROW_EST")).thenAnswer(invocation -> {
            Long value = rows[cursor.get()].rows();
            lastWasNull.set(value == null);
            return value == null ? 0L : value;
        });
        when(rs.getLong("TOTAL_BYTES")).thenAnswer(invocation -> {
            Long value = rows[cursor.get()].bytes();
            lastWasNull.set(value == null);
            return value == null ? 0L : value;
        });
        when(rs.wasNull()).thenAnswer(invocation -> lastWasNull.get());
        return conn;
    }

    private static Map<String, MetadataReader.TableStat> stats(MetadataReader reader, Connection conn)
            throws SQLException {
        return reader.estimateTableStats(conn, null);
    }

    @Test
    void mysqlReadsRowsAndBytesAndKeepsUnknownAsNull() throws SQLException {
        Connection conn = connectionWithRows(
                new Row("orders", 1_000_000L, 8_388_608L),
                new Row("scratch", null, null));

        Map<String, MetadataReader.TableStat> stats = stats(new MySQLMetadataReader(), conn);

        assertThat(stats).containsOnlyKeys("orders", "scratch");
        assertThat(stats.get("orders").rows()).isEqualTo(1_000_000L);
        assertThat(stats.get("orders").bytes()).isEqualTo(8_388_608L);
        assertThat(stats.get("scratch").rows()).isNull();
        assertThat(stats.get("scratch").bytes()).isNull();
    }

    @Test
    void postgresTreatsNegativeRelTuplesAsUnknown() throws SQLException {
        Connection conn = connectionWithRows(new Row("never_analyzed", -1L, 65_536L));

        Map<String, MetadataReader.TableStat> stats = stats(new PostgresMetadataReader(), conn);

        assertThat(stats.get("never_analyzed").rows()).isNull();
        assertThat(stats.get("never_analyzed").bytes()).isEqualTo(65_536L);
    }

    @Test
    void oracleTreatsZeroAsUnknown() throws SQLException {
        Connection conn = connectionWithRows(new Row("unanalyzed", 0L, 0L));

        Map<String, MetadataReader.TableStat> stats = stats(new OracleMetadataReader(), conn);

        assertThat(stats.get("unanalyzed").rows()).isNull();
        assertThat(stats.get("unanalyzed").bytes()).isNull();
    }

    @Test
    void sqlServerReadsPartitionSum() throws SQLException {
        Connection conn = connectionWithRows(new Row("dbo.Orders", 42L, 16_384L));

        Map<String, MetadataReader.TableStat> stats = stats(new SqlServerMetadataReader(), conn);

        assertThat(stats.get("dbo.Orders").rows()).isEqualTo(42L);
        assertThat(stats.get("dbo.Orders").bytes()).isEqualTo(16_384L);
    }

    @Test
    void db2LeavesBytesUnknown() throws SQLException {
        Connection conn = connectionWithRows(new Row("ORDERS", 7L, null));

        Map<String, MetadataReader.TableStat> stats = stats(new Db2MetadataReader(), conn);

        assertThat(stats.get("ORDERS").rows()).isEqualTo(7L);
        assertThat(stats.get("ORDERS").bytes()).isNull();
    }

    @Test
    void aBrokenDictionaryYieldsAnEmptyMapNotACountStar() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString())).thenThrow(new SQLException("no catalog access"));

        assertThat(stats(new MySQLMetadataReader(), conn)).isEmpty();
        assertThat(stats(new PostgresMetadataReader(), conn)).isEmpty();
        assertThat(stats(new OracleMetadataReader(), conn)).isEmpty();
        assertThat(stats(new SqlServerMetadataReader(), conn)).isEmpty();
        assertThat(stats(new Db2MetadataReader(), conn)).isEmpty();
    }

    @Test
    void aReaderWithoutADictionaryReturnsEmpty() throws SQLException {
        Connection conn = connectionWithRows(new Row("t", 1L, 1L));

        assertThat(stats(new GenericMetadataReader(), conn)).isEmpty();
    }
}
