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

    private ResultSet emptyResultSet() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }
}
