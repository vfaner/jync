package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.sync.ConnectionProvider;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * Stop requested MID-RESULT-SET must not flush a partially folded transaction.
 *
 * <p>The change scan streams rows grouped by commit LSN. If {@code requestStop()} lands
 * after row 1 of a 2-row transaction, the fold holds only that transaction's first image
 * (an op=3 update-before makes the fold DELETE a row the source still has). The old code
 * flushed the fold unconditionally after the CI loop and then advanced the persisted
 * position to that transaction's LSN — the target kept a ghost/deleted row and, worse,
 * after restart the watermark skipped the transaction's remaining rows forever.
 *
 * <p>Correct behavior: once a scan was cut short, flush NOTHING and advance NOTHING; the
 * whole window re-reads on the next start and lands through idempotent upserts.
 */
class SqlServerCdcConsumerStopTest {

    private static final String FROM_LSN = "0x00000000000000000000";
    private static final String MAX_LSN  = "0x0000002857ae025800ff";
    private static final String TX_A     = "0x0000002857ae025800a0";
    private static final String TX_B     = "0x0000002857ae025800b0";

    private Connection sourceConn;
    private Connection targetConn;
    private ConnectionProvider provider;
    private CdcPositionStore positionStore;
    private SqlServerCdcConsumer consumer;

    @BeforeEach
    void setUp() throws SQLException {
        sourceConn = mock(Connection.class);

        PreparedStatement maxPs = mock(PreparedStatement.class);
        ResultSet maxRs = mock(ResultSet.class);
        when(maxRs.next()).thenReturn(true);
        when(maxRs.getString(1)).thenReturn(MAX_LSN);
        when(maxPs.executeQuery()).thenReturn(maxRs);
        when(sourceConn.prepareStatement(contains("fn_cdc_get_max_lsn"))).thenReturn(maxPs);

        PreparedStatement discoveryPs = mock(PreparedStatement.class);
        when(discoveryPs.executeQuery()).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn("dbo");
            when(rs.getString(2)).thenReturn("t1");
            when(rs.getString(3)).thenReturn("dbo_t1");
            return rs;
        });
        when(sourceConn.prepareStatement(contains("cdc.change_tables"))).thenReturn(discoveryPs);

        // Two streamed rows: TX_A is a complete insert; TX_B is the FIRST HALF of an UPDATE
        // (op=3 update-before). Delivering row 2 flips the stop flag, so the scan exits
        // before TX_B's op=4 ever arrives — the fold is provably incomplete.
        PreparedStatement changesPs = mock(PreparedStatement.class);
        when(changesPs.executeQuery()).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            ResultSetMetaData md = mock(ResultSetMetaData.class);
            when(md.getColumnCount()).thenReturn(3);
            when(md.getColumnName(1)).thenReturn("__$start_lsn");
            when(md.getColumnName(2)).thenReturn("__$operation");
            when(md.getColumnName(3)).thenReturn("id");
            when(rs.getMetaData()).thenReturn(md);
            when(rs.next()).thenAnswer(new Answer<>() {
                int n;
                @Override public Boolean answer(InvocationOnMock i) {
                    n++;
                    if (n == 2) {
                        consumer.requestStop();
                    }
                    return n <= 2;
                }
            });
            when(rs.getString(1)).thenReturn(TX_A, TX_B);
            when(rs.getInt(2)).thenReturn(2, 3); // 2=insert, 3=update-before
            when(rs.getObject("id")).thenReturn(1, 2);
            return rs;
        });
        when(sourceConn.prepareStatement(contains("fn_cdc_get_all_changes_")))
                .thenReturn(changesPs);

        TableMeta meta = new TableMeta();
        meta.setName("t1");
        meta.setSchema("dbo");
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(java.sql.Types.INTEGER);
        id.setPrimaryKey(true);
        meta.setColumns(new ArrayList<>(List.of(id)));
        meta.setPrimaryKeys(new ArrayList<>(List.of("id")));
        com.qqmu.jync.service.metadata.MetadataReader reader =
                mock(com.qqmu.jync.service.metadata.MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(meta);
        DataSourceManager dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(any())).thenReturn(mock(Connection.class));

        SyncContext ctx = mock(SyncContext.class);
        SyncConfig config = mock(SyncConfig.class);
        when(config.includesTable("t1")).thenReturn(true);
        when(config.targetTableName("t1")).thenReturn("t1");
        when(ctx.getConfig()).thenReturn(config);
        when(ctx.getSourceReader()).thenReturn(reader);
        com.qqmu.jync.service.converter.SqlDialect dialect =
                mock(com.qqmu.jync.service.converter.SqlDialect.class);
        when(dialect.getUpsertSql(any(), any(), anyList(), anyList()))
                .thenReturn("MERGE INTO t1 ...");
        when(dialect.getUpsertSql(any(), any(), anyList(), anyList(), any()))
                .thenReturn("MERGE INTO t1 ...");
        when(dialect.upsertBindOrder(anyList(), anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getTargetDialect()).thenReturn(dialect);
        when(ctx.getBatchSize()).thenReturn(100);
        provider = mock(ConnectionProvider.class);
        targetConn = mock(Connection.class);
        when(targetConn.getAutoCommit()).thenReturn(true);
        // A fully wired target: if the buggy flush runs, commit() succeeds and the
        // verification below fails on the exact unwanted interaction, not on an NPE.
        when(targetConn.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        when(provider.open(any())).thenReturn(targetConn);
        when(ctx.getConnectionProvider()).thenReturn(provider);

        Project project = new Project();
        project.setId(1L);
        project.setName("mssql-stop");
        positionStore = mock(CdcPositionStore.class);
        consumer = new SqlServerCdcConsumer(project,
                mock(SyncLockService.LockHandle.class), ctx,
                positionStore, mock(CdcBaselineService.class),
                mock(SyncTaskStore.class), mock(SyncStateWriter.class),
                dsm, new CdcRuntimeStats(), new SyncProperties());
    }

    @Test
    void stopMidTransactionFlushesNothingAndAdvancesNothing() throws Exception {
        SqlServerCdcConsumer.DrainResult result = consumer.drain(sourceConn, FROM_LSN);

        // No target connection may even be opened: every folded transaction is suspect once
        // a scan was truncated, so nothing can be committed.
        verifyNoInteractions(provider);
        verify(targetConn, never()).commit();
        verify(positionStore, never()).advanceLsn(any(), anyString());
        assertThat(result.nextLsn).isNull();
    }
}
