package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.TableMeta;

/**
 * The ROWDEPENDENCIES probe behind the ORA_ROWSCN cursor option (task book M4).
 *
 * <p>The flag drives a real strategy decision, so all three outcomes must be distinct:
 * ENABLED → row-level SCN usable, DISABLED → block-level only (never offered), and any
 * probe failure or non-Oracle product → null, which keeps the resolver on its classic path
 * instead of guessing.
 */
class OracleMetadataReaderRowScnTest {

    private final OracleMetadataReader reader = new OracleMetadataReader();

    private static ResultSet emptyResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    /** A connection whose JDBC metadata reports an empty table — enough for super.readTable. */
    private static Connection mockConnection(String productName) throws SQLException {
        // Built up front: calling emptyResultSet() inside a thenReturn() would start a nested
        // stubbing mid-chain, which Mockito rejects as UnfinishedStubbing. One shared empty
        // ResultSet is fine — the reader only iterates it (zero rows) and close() is a no-op.
        ResultSet empty = emptyResultSet();
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn(productName);
        when(md.getTables(any(), any(), any(), any())).thenReturn(empty);
        when(md.getPrimaryKeys(any(), any(), any())).thenReturn(empty);
        when(md.getColumns(any(), any(), any(), any())).thenReturn(empty);
        when(md.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(empty);
        when(md.getImportedKeys(any(), any(), any())).thenReturn(empty);
        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(md);
        return conn;
    }

    private static void stubDependenciesQuery(Connection conn, String value) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(value != null).thenReturn(false);
        if (value != null) {
            when(rs.getString(1)).thenReturn(value);
        }
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(contains("DEPENDENCIES FROM ALL_TABLES"))).thenReturn(ps);
    }

    @Test
    void anEnabledRowdependenciesTableIsMarkedRowScnCapable() throws Exception {
        Connection conn = mockConnection("Oracle");
        stubDependenciesQuery(conn, "ENABLED");

        TableMeta table = reader.readTable(conn, "HR", "EMP");

        assertThat(table.getRowLevelScn()).isTrue();
    }

    @Test
    void aDisabledRowdependenciesTableIsMarkedNotCapable() throws Exception {
        Connection conn = mockConnection("Oracle");
        stubDependenciesQuery(conn, "DISABLED");

        TableMeta table = reader.readTable(conn, "HR", "EMP");

        // Block-level SCNs move on any row's change: polling on them would re-deliver
        // unrelated rows forever, so this must not be offered as a cursor.
        assertThat(table.getRowLevelScn()).isFalse();
    }

    @Test
    void oracleFamilyLookAlikesAreNotProbedAtAll() throws Exception {
        // 达梦 mimics the Oracle dictionary but not ORA_ROWSCN row semantics; the whitelist
        // is the product name, mirroring the task book's DatabaseType-whitelist rule.
        Connection conn = mockConnection("DM DBMS");

        TableMeta table = reader.readTable(conn, "HR", "EMP");

        assertThat(table.getRowLevelScn()).isNull();
        verify(conn, never()).prepareStatement(anyString());
    }

    @Test
    void aFailingProbeLeavesTheFlagNullInsteadOfFailingTheRead() throws Exception {
        Connection conn = mockConnection("Oracle");
        when(conn.prepareStatement(contains("DEPENDENCIES FROM ALL_TABLES")))
                .thenThrow(new SQLException("ORA-00942: table or view does not exist"));

        TableMeta[] holder = new TableMeta[1];
        assertThatCode(() -> holder[0] = reader.readTable(conn, "HR", "EMP"))
                .doesNotThrowAnyException();
        assertThat(holder[0].getRowLevelScn()).isNull();
    }
}
