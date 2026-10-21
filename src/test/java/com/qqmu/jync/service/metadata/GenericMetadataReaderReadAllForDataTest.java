package com.qqmu.jync.service.metadata;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.DatabaseMeta;

/**
 * The on-demand read behind a cycle that has structure sync switched off.
 *
 * <p>A data-only cycle consumes the columns, the primary key, autoincrement enrichment and the
 * row-level SCN probe; the index, foreign-key and comment dictionaries are read only to feed
 * structure change detection, which does not run. Fetching them anyway is two to four
 * dictionary round trips per table per poll — on a two-second poll against a customer's
 * production source that is a lot of nothing.
 */
class GenericMetadataReaderReadAllForDataTest {

    private final GenericMetadataReader reader = new GenericMetadataReader();

    private Connection connection() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn(null);

        ResultSet none = emptyResultSet();
        when(md.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(none);
        when(md.getPrimaryKeys(any(), any(), anyString())).thenReturn(none);
        when(md.getColumns(any(), any(), anyString(), anyString())).thenReturn(none);
        when(md.getIndexInfo(any(), any(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(none);
        when(md.getImportedKeys(any(), any(), anyString())).thenReturn(none);
        return conn;
    }

    private static ResultSet emptyResultSet() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    @Test
    void theDataReadSkipsTheIndexAndForeignKeyDictionaries() throws Exception {
        Connection conn = connection();
        DatabaseMetaData md = conn.getMetaData();

        reader.readAllForData(conn, null, List.of("ORDERS"), null, null, false, false);

        verify(md, never()).getIndexInfo(any(), any(), anyString(), anyBoolean(), anyBoolean());
        verify(md, never()).getImportedKeys(any(), any(), anyString());
    }

    @Test
    void theFullReadStillAsksForTheIndexAndForeignKeyDictionaries() throws Exception {
        Connection conn = connection();
        DatabaseMetaData md = conn.getMetaData();

        reader.readAll(conn, null, List.of("ORDERS"), null, null, false, false);

        verify(md).getIndexInfo(any(), any(), anyString(), anyBoolean(), anyBoolean());
        verify(md).getImportedKeys(any(), any(), anyString());
    }

    @Test
    void theDataReadStillCarriesColumnsAndThePrimaryKey() throws Exception {
        // The data path needs both: the cursor strategy and the upsert predicate are built
        // from the primary key, and the row mapper from the column list.
        Connection conn = connection();
        DatabaseMetaData md = conn.getMetaData();
        ResultSet columns = mock(ResultSet.class);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("TABLE_NAME")).thenReturn("ORDERS");
        when(columns.getString("COLUMN_NAME")).thenReturn("ID");
        when(columns.getString("TYPE_NAME")).thenReturn("BIGINT");
        when(columns.getInt("DATA_TYPE")).thenReturn(java.sql.Types.BIGINT);
        when(columns.getInt("ORDINAL_POSITION")).thenReturn(1);
        when(md.getColumns(any(), any(), anyString(), anyString())).thenReturn(columns);

        ResultSet pk = mock(ResultSet.class);
        when(pk.next()).thenReturn(true, false);
        when(pk.getString("TABLE_NAME")).thenReturn("ORDERS");
        when(pk.getString("COLUMN_NAME")).thenReturn("ID");
        when(pk.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(md.getPrimaryKeys(any(), any(), anyString())).thenReturn(pk);

        DatabaseMeta meta = reader.readAllForData(conn, null, List.of("ORDERS"), null, null,
                false, false);

        org.assertj.core.api.Assertions.assertThat(meta.getTables()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(meta.getTables().get(0).getPrimaryKeys())
                .containsExactly("ID");
        org.assertj.core.api.Assertions.assertThat(meta.getTables().get(0).getColumns())
                .extracting(c -> c.getName()).containsExactly("ID");
    }
}
