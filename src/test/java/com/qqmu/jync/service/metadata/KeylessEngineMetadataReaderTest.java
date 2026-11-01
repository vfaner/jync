package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Doris, ClickHouse, Hive, Impala, Elasticsearch SQL and Atlas SQL have no unique constraint the
 * sync may rely on: what their drivers report through {@code getPrimaryKeys} is a sort key or an
 * index hint. Believing it would let the loader pick a keyset plan the table cannot honour, and
 * would let a target upsert collapse rows that are distinct in the source — silent data loss.
 * The reader therefore never asks.
 */
class KeylessEngineMetadataReaderTest {

    @Test
    void itClaimsExactlyTheSixEnginePresets() {
        KeylessEngineMetadataReader reader = new KeylessEngineMetadataReader();

        for (DatabaseType type : List.of(DatabaseType.DORIS, DatabaseType.CLICKHOUSE,
                DatabaseType.HIVE, DatabaseType.IMPALA,
                DatabaseType.ELASTICSEARCH, DatabaseType.MONGODB)) {
            assertThat(reader.supports(type)).as(type.name()).isTrue();
        }
        for (DatabaseType type : List.of(DatabaseType.MYSQL, DatabaseType.H2,
                DatabaseType.MONGODB_NATIVE, DatabaseType.CUSTOM)) {
            assertThat(reader.supports(type)).as(type.name()).isFalse();
        }
    }

    @Test
    void aDriverReportedPrimaryKeyIsDiscarded() throws Exception {
        ResultSet empty = emptyResultSet();
        ResultSet pkRs = mock(ResultSet.class);
        when(pkRs.next()).thenReturn(true).thenReturn(false);
        when(pkRs.getString("TABLE_NAME")).thenReturn("events");
        when(pkRs.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("ID");

        ResultSet colRs = mock(ResultSet.class);
        when(colRs.next()).thenReturn(true).thenReturn(false);
        when(colRs.getString("TABLE_NAME")).thenReturn("events");
        when(colRs.getString("COLUMN_NAME")).thenReturn("ID");
        when(colRs.getString("TYPE_NAME")).thenReturn("BIGINT");
        when(colRs.getInt("DATA_TYPE")).thenReturn(java.sql.Types.BIGINT);
        when(colRs.getInt("ORDINAL_POSITION")).thenReturn(1);

        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getTables(any(), any(), any(), any())).thenReturn(empty);
        when(metaData.getPrimaryKeys(any(), any(), any())).thenReturn(pkRs);
        when(metaData.getColumns(any(), any(), any(), any())).thenReturn(colRs);
        when(metaData.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(empty);
        when(metaData.getImportedKeys(any(), any(), any())).thenReturn(empty);

        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(metaData);

        TableMeta table = new KeylessEngineMetadataReader().readTable(conn, null, "events");

        assertThat(table.getPrimaryKeys()).isEmpty();
        assertThat(table.getColumns()).singleElement()
                .satisfies(col -> assertThat(col.isPrimaryKey()).isFalse());
        verify(metaData, never()).getPrimaryKeys(any(), any(), any());
    }

    @Test
    void theGenericReaderStillTrustsTheDriver() throws Exception {
        // The hook must default to the old behaviour: every other product keeps its real key.
        ResultSet empty = emptyResultSet();
        ResultSet pkRs = mock(ResultSet.class);
        when(pkRs.next()).thenReturn(true).thenReturn(false);
        when(pkRs.getString("TABLE_NAME")).thenReturn("T1");
        when(pkRs.getShort("KEY_SEQ")).thenReturn((short) 1);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("ID");

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

        assertThat(table.getPrimaryKeys()).containsExactly("ID");
    }

    private static ResultSet emptyResultSet() throws java.sql.SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }
}
