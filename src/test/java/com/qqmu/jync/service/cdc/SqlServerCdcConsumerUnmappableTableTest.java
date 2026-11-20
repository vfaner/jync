package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * 源表没有主键(或元数据读不到)时,它的每一行都必须按“不支持的表”跳过并计数 ——
 * 不能因为拿不到绑定就把整个项目的 drain 抛进每轮重试。
 *
 * <p>{@link SqlServerCdcConsumer.BindingSupplier#binding} 对这类表返回 {@code null},
 * 由 scan 循环逐行计入 {@code unsupportedTables}。此前该循环在判定 {@code reader == null}
 * 之前先解引用 {@code binding.pkNames()},空绑定直接抛 NPE:异常被归类为 TRANSIENT、
 * 不计入应用失败,整个项目里所有被跟踪的表就此静默停止同步,永远不 park 也不推进水位。
 */
class SqlServerCdcConsumerUnmappableTableTest {

    private static final String FROM_LSN = "0x00000000000000000000";
    private static final String MAX_LSN  = "0x0000002857ae025800ff";
    private static final String TX_LSN   = "0x0000002857ae025800a0";

    private Connection sourceConn;
    private Connection targetConn;
    private CdcRuntimeStats stats;
    private CdcPositionStore positionStore;
    private SqlServerCdcConsumer consumer;

    private List<String> capturedColumns;

    @BeforeEach
    void setUp() throws SQLException {
        sourceConn = mock(Connection.class);
        targetConn = mock(Connection.class);
        capturedColumns = new ArrayList<>(List.of("id", "name"));

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
            when(rs.getString(4)).thenReturn("0x0000002857a000000000");
            return rs;
        });
        when(sourceConn.prepareStatement(contains("cdc.change_tables"))).thenReturn(discoveryPs);

        PreparedStatement changesPs = mock(PreparedStatement.class);
        when(changesPs.executeQuery()).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            ResultSetMetaData md = mock(ResultSetMetaData.class);
            when(md.getColumnCount()).thenReturn(capturedColumns.size() + 2);
            when(md.getColumnName(1)).thenReturn("__$start_lsn");
            when(md.getColumnName(2)).thenReturn("__$operation");
            for (int i = 0; i < capturedColumns.size(); i++) {
                when(md.getColumnName(i + 3)).thenReturn(capturedColumns.get(i));
            }
            when(rs.getMetaData()).thenReturn(md);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn(TX_LSN);
            when(rs.getInt(2)).thenReturn(2); // 2 = INSERT
            return rs;
        });
        when(sourceConn.prepareStatement(contains("fn_cdc_get_all_changes_")))
                .thenReturn(changesPs);

        // —— 元数据:dbo.t1 有列、但没有主键 → binding 为 null ——
        MetadataReader reader = mock(MetadataReader.class);
        TableMeta meta = new TableMeta();
        meta.setName("t1");
        meta.setSchema("dbo");
        meta.setColumns(new ArrayList<>(List.of(
                column("id", java.sql.Types.INTEGER),
                column("name", java.sql.Types.VARCHAR))));
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(meta);
        DataSourceManager dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(any())).thenReturn(mock(Connection.class));

        SyncContext ctx = mock(SyncContext.class);
        SyncConfig config = mock(SyncConfig.class);
        when(config.includesTable("t1")).thenReturn(true);
        when(config.targetTableName("t1")).thenReturn("t1");
        when(ctx.getConfig()).thenReturn(config);
        when(ctx.getSourceReader()).thenReturn(reader);

        stats = new CdcRuntimeStats();
        positionStore = mock(CdcPositionStore.class);
        Project project = new Project();
        project.setId(1L);
        project.setName("mssql-1");
        consumer = new SqlServerCdcConsumer(project,
                mock(SyncLockService.LockHandle.class), ctx,
                positionStore, mock(CdcBaselineService.class),
                mock(SyncTaskStore.class), mock(SyncStateWriter.class),
                dsm, stats, new SyncProperties());
    }

    private static ColumnMeta column(String name, int jdbcType) {
        ColumnMeta c = new ColumnMeta();
        c.setName(name);
        c.setJdbcType(jdbcType);
        return c;
    }

    @Test
    void aTableWithoutAPrimaryKeyIsCountedAsUnsupportedInsteadOfFailingTheDrain()
            throws Exception {
        SqlServerCdcConsumer.DrainResult result = consumer.drain(sourceConn, FROM_LSN);

        // 不能写目标端:没有主键就无法构造幂等的 upsert。
        verify(targetConn, never()).prepareStatement(anyString());
        verify(targetConn, never()).commit();
        // 但事件不能无声消失:状态面板点名该表。
        assertThat(stats.unsupportedTables()).contains("dbo.t1");
        // 整段窗口仍算读完:水位前进,项目不会每轮重扫同一个窗口。
        assertThat(result.nextLsn).isEqualTo(MAX_LSN);
        // 而且必须落盘。只推进内存 nextLsn 时,重启会回到旧水位;等 CDC 清理作业
        // 把这段时间的变更清掉,重启后的 22054 就会被误判成 PURGED 停机。
        verify(positionStore).advanceLsn(1L, MAX_LSN);
    }
}
