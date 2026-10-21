package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Oracle adds two comment dictionary queries per table on top of the generic read. Those
 * comments exist only for the structural diff; a structure-off cycle must not pay for them.
 */
class OracleMetadataReaderReadAllForDataTest {

    private final OracleMetadataReader reader = new OracleMetadataReader();

    private Connection connection() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn(null);
        when(md.getDatabaseProductName()).thenReturn("Oracle");

        ResultSet none = mock(ResultSet.class);
        when(none.next()).thenReturn(false);
        when(md.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(none);
        when(md.getPrimaryKeys(any(), any(), anyString())).thenReturn(none);
        when(md.getColumns(any(), any(), anyString(), anyString())).thenReturn(none);
        when(md.getIndexInfo(any(), any(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(none);
        when(md.getImportedKeys(any(), any(), anyString())).thenReturn(none);

        // Every dictionary query (comments, identity, triggers, row-level probe) answers with
        // no rows: only which statements were issued matters here.
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(none);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        return conn;
    }

    private static List<String> issuedSql(Connection conn) throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(conn, org.mockito.Mockito.atLeastOnce())
                .prepareStatement(sql.capture());
        return sql.getAllValues();
    }

    @Test
    void theDataReadSkipsTheCommentDictionaries() throws Exception {
        Connection conn = connection();

        reader.readAllForData(conn, "APP", List.of("ORDERS"), null, null, false, false);

        assertThat(issuedSql(conn))
                .as("structure sync is off: comments are never compared")
                .noneMatch(s -> s.contains("ALL_TAB_COMMENTS") || s.contains("ALL_COL_COMMENTS"));
    }

    @Test
    void theDataReadKeepsTheRowLevelScnProbe() throws Exception {
        // CursorStrategyResolver picks the SCN strategy from this probe, so the data path
        // needs it even when structure sync is off.
        Connection conn = connection();

        reader.readAllForData(conn, "APP", List.of("ORDERS"), null, null, false, false);

        assertThat(issuedSql(conn)).anyMatch(s -> s.contains("ALL_TABLES"));
    }

    @Test
    void theFullReadStillReadsTheComments() throws Exception {
        Connection conn = connection();

        reader.readAll(conn, "APP", List.of("ORDERS"), null, null, false, false);

        assertThat(issuedSql(conn)).anyMatch(s -> s.contains("ALL_TAB_COMMENTS"));
    }
}
