package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.TableMeta;

/**
 * The name arguments of getTables/getColumns are JDBC <em>patterns</em>: an underscore
 * matches any single character. A driver therefore answers a request for {@code user_info}
 * with {@code user-info}'s rows too, and merging those would silently give the table a
 * column it does not have.
 */
class GenericMetadataReaderTest {

    private final GenericMetadataReader reader = new GenericMetadataReader();

    @Test
    void columnsFromWildcardNameCollisionsAreFilteredOut() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn(null);

        ResultSet none = emptyResultSet();
        when(md.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(none);
        when(md.getPrimaryKeys(any(), any(), anyString())).thenReturn(none);
        when(md.getIndexInfo(any(), any(), anyString(), anyBoolean(), anyBoolean())).thenReturn(none);
        when(md.getImportedKeys(any(), any(), anyString())).thenReturn(none);

        // One row for the requested table, one for a name that only matches it as a pattern.
        ResultSet columns = mock(ResultSet.class);
        when(columns.next()).thenReturn(true, true, false);
        when(columns.getString("TABLE_NAME")).thenReturn("user_info", "user-info");
        when(columns.getString("TABLE_SCHEM")).thenReturn(null, null);
        when(columns.getString("COLUMN_NAME")).thenReturn("id", "evil");
        when(columns.getString("TYPE_NAME")).thenReturn("BIGINT", "VARCHAR");
        when(columns.getInt("DATA_TYPE")).thenReturn(java.sql.Types.BIGINT, java.sql.Types.VARCHAR);
        when(columns.getInt("ORDINAL_POSITION")).thenReturn(1, 1);
        when(md.getColumns(any(), any(), anyString(), anyString())).thenReturn(columns);

        TableMeta table = reader.readTable(conn, null, "user_info");

        assertThat(table.getColumns()).extracting(c -> c.getName()).containsExactly("id");
    }

    @Test
    void aReportedSchemaThatDiffersFromTheRequestedOneIsFilteredOut() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn(null);

        ResultSet none = emptyResultSet();
        when(md.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(none);
        when(md.getPrimaryKeys(any(), any(), anyString())).thenReturn(none);
        when(md.getIndexInfo(any(), any(), anyString(), anyBoolean(), anyBoolean())).thenReturn(none);
        when(md.getImportedKeys(any(), any(), anyString())).thenReturn(none);

        ResultSet columns = mock(ResultSet.class);
        when(columns.next()).thenReturn(true, true, false);
        // Same name in two schemas; only the requested one may survive. A driver that
        // reports no schema at all (MySQL) must not be filtered — that is the other test.
        when(columns.getString("TABLE_NAME")).thenReturn("orders", "orders");
        when(columns.getString("TABLE_SCHEM")).thenReturn("sales", "audit");
        when(columns.getString("COLUMN_NAME")).thenReturn("id", "id");
        when(columns.getString("TYPE_NAME")).thenReturn("BIGINT", "BIGINT");
        when(columns.getInt("DATA_TYPE")).thenReturn(java.sql.Types.BIGINT, java.sql.Types.BIGINT);
        when(columns.getInt("ORDINAL_POSITION")).thenReturn(1, 1);
        when(md.getColumns(any(), any(), anyString(), anyString())).thenReturn(columns);

        TableMeta table = reader.readTable(conn, "sales", "orders");

        assertThat(table.getColumns()).hasSize(1);
    }

    @Test
    void theCountFallbackRunsAtMostOncePerTtlWindow() throws Exception {
        // 统计信息不可用时 estimateRowCount 兜底走 SELECT COUNT(*) —— 全表扫描。
        // 轮询周期以秒计，兜底结果必须在 TTL 窗口内复用，否则大表每 2 秒被全扫一次。
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getURL()).thenReturn("jdbc:mock:db");
        when(md.getIdentifierQuoteString()).thenReturn("\"");
        java.sql.Statement st = mock(java.sql.Statement.class);
        when(conn.createStatement()).thenReturn(st);
        ResultSet rs = mock(ResultSet.class);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(42L);

        assertThat(reader.estimateRowCount(conn, "S", "T")).isEqualTo(42L);
        assertThat(reader.estimateRowCount(conn, "S", "T")).isEqualTo(42L);

        org.mockito.Mockito.verify(st, org.mockito.Mockito.times(1)).executeQuery(anyString());
    }

    private ResultSet emptyResultSet() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }
}
