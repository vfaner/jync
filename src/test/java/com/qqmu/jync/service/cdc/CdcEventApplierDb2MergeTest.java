package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.Db2Dialect;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * CDC replay into DB2 prepares the same MERGE as the polling write path, so it must carry
 * the same explicit CAST markers: untyped markers in {@code USING (VALUES ...)} get
 * re-resolved across the ON predicate / UPDATE / INSERT branches and fail with SQL0418 /
 * SQLSTATE 42610 ("FCS already resolved to different type").
 */
class CdcEventApplierDb2MergeTest {

    @Test
    void replayMergeWrapsEveryValuesMarkerInACast() throws Exception {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(java.sql.Types.BIGINT);
        id.setPrimaryKey(true);
        ColumnMeta fcs = new ColumnMeta();
        fcs.setName("FCS");
        fcs.setJdbcType(java.sql.Types.VARCHAR);
        fcs.setSize(36);
        TableMeta meta = new TableMeta();
        meta.setName("JYNC_SYNC_TEST");
        meta.getColumns().add(id);
        meta.getColumns().add(fcs);
        meta.getPrimaryKeys().add("ID");
        CdcTableBinding binding = CdcTableBinding.of("app", "JYNC_SYNC_TEST", meta,
                "JYNC_SYNC_TEST", null);

        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceType(DatabaseType.MYSQL)
                .targetDialect(new Db2Dialect())
                .batchSize(50)
                .build();
        CdcEventApplier applier = new CdcEventApplier(ctx, new CdcRuntimeStats());

        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(ps);
        when(ps.executeBatch()).thenReturn(new int[] {1});

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", 1L);
        row.put("FCS", "abc");
        applier.applyUpserts(conn, binding, List.of(row));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains(
                "USING (VALUES (CAST(? AS BIGINT), CAST(? AS VARCHAR(36)))) AS s");
    }
}
