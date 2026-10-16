package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * P2/P3: a driver that reports a getProcedureColumns row with PROCEDURE_NAME = null (allowed by
 * the JDBC spec) used to throw a NullPointerException inside stripPackagePrefix and abort the
 * read. The row must simply be skipped.
 */
class GenericMetadataReaderNullNameTest {

    @Test
    void aNullProcedureNameRowIsSkippedInsteadOfThrowing() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true).thenReturn(false);
        when(rs.getString("PROCEDURE_NAME")).thenReturn(null);

        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getProcedureColumns(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(rs);

        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(metaData);

        GenericMetadataReader reader = new GenericMetadataReader();

        ProcedureMeta proc = reader.readProcedure(conn, "dbo", "P1");

        assertThat(proc.getName()).isEqualTo("P1");
        assertThat(proc.getParameters()).isEmpty();
    }

    @Test
    void aPrimaryKeyRowWithNullColumnNameIsSkippedInsteadOfThrowing() throws Exception {
        // JDBC permits a null COLUMN_NAME; the old code stored it and then NPE'd on
        // pk.toUpperCase() before any column was read, failing the whole table.
        ResultSet empty = emptyResultSet();
        ResultSet pkRs = mock(ResultSet.class);
        when(pkRs.next()).thenReturn(true).thenReturn(false);
        when(pkRs.getString("TABLE_NAME")).thenReturn("T1");
        when(pkRs.getString("TABLE_SCHEM")).thenReturn("dbo");
        when(pkRs.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(pkRs.getString("COLUMN_NAME")).thenReturn(null);

        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getTables(any(), any(), any(), any())).thenReturn(empty);
        when(metaData.getPrimaryKeys(any(), any(), any())).thenReturn(pkRs);
        when(metaData.getColumns(any(), any(), any(), any())).thenReturn(empty);
        when(metaData.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(empty);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(empty);

        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(metaData);

        TableMeta table = new GenericMetadataReader().readTable(conn, "dbo", "T1");

        assertThat(table.getPrimaryKeys()).isEmpty();
    }

    @Test
    void aColumnRowWithNullColumnNameIsSkippedInsteadOfThrowing() throws Exception {
        ResultSet empty = emptyResultSet();
        ResultSet colRs = mock(ResultSet.class);
        when(colRs.next()).thenReturn(true).thenReturn(true).thenReturn(false);
        when(colRs.getString("TABLE_NAME")).thenReturn("T1");
        when(colRs.getString("TABLE_SCHEM")).thenReturn("dbo");
        when(colRs.getString("COLUMN_NAME")).thenReturn(null, "name");
        when(colRs.getString("TYPE_NAME")).thenReturn("VARCHAR", "VARCHAR");
        when(colRs.getInt("DATA_TYPE")).thenReturn(java.sql.Types.VARCHAR);
        when(colRs.getInt("COLUMN_SIZE")).thenReturn(100);
        when(colRs.getInt("DECIMAL_DIGITS")).thenReturn(0);
        when(colRs.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNullable);
        when(colRs.getInt("ORDINAL_POSITION")).thenReturn(1, 2);
        when(colRs.getString("IS_AUTOINCREMENT")).thenReturn("NO", "NO");

        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getTables(any(), any(), any(), any())).thenReturn(empty);
        when(metaData.getPrimaryKeys(any(), any(), any())).thenReturn(empty);
        when(metaData.getColumns(any(), any(), any(), any())).thenReturn(colRs);
        when(metaData.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(empty);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(empty);

        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(metaData);

        TableMeta table = new GenericMetadataReader().readTable(conn, "dbo", "T1");

        assertThat(table.getColumns()).hasSize(1);
        assertThat(table.getColumns().get(0).getName()).isEqualTo("name");
    }

    private static ResultSet emptyResultSet() throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }
}
