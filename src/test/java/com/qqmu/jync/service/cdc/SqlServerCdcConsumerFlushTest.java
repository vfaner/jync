package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.ConnectionProvider;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * P2-3:flush 失败后 binding 缓存必须失效。
 *
 * <p>BindingSupplier 的缓存永不失效:源库 DDL 删列后,缓存 binding 构造的 upsert
 * SQL 仍带已删列,每次重试都重放同一条必败语句,直到 strike-out 把项目 park 进
 * ERROR——而只要重读一次元数据就能自愈。修复:flushTransaction 失败 rethrow 前
 * 把本事务涉及表的缓存条目逐出,下一次 drain 用新元数据重建 binding。
 *
 * <p>全链路 mock:源 conn(maxLsn/discovery/changes 三路分发)+ 元数据 reader +
 * 目标 conn(prepareStatement 抛「Invalid column name」模拟删列后的必然失败)。
 */
class SqlServerCdcConsumerFlushTest {

    private static final String FROM_LSN = "0x00000000000000000000";
    private static final String MAX_LSN  = "0x0000002857ae025800ff";
    private static final String TX_LSN   = "0x0000002857ae025800a0";

    private Connection sourceConn;
    private Connection targetConn;
    private MetadataReader reader;
    private SqlServerCdcConsumer consumer;

    @BeforeEach
    void setUp() throws SQLException {
        sourceConn = mock(Connection.class);
        targetConn = mock(Connection.class);

        // —— 源库三路分发 ——
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

        PreparedStatement changesPs = mock(PreparedStatement.class);
        when(changesPs.executeQuery()).thenAnswer(inv -> {
            ResultSet rs = mock(ResultSet.class);
            ResultSetMetaData md = mock(ResultSetMetaData.class);
            when(md.getColumnCount()).thenReturn(3);
            when(md.getColumnName(1)).thenReturn("__$start_lsn");
            when(md.getColumnName(2)).thenReturn("__$operation");
            when(md.getColumnName(3)).thenReturn("id");
            when(rs.getMetaData()).thenReturn(md);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn(TX_LSN);
            when(rs.getInt(2)).thenReturn(2); // __$operation: 2=INSERT(1=DELETE!)
            when(rs.getObject("id")).thenReturn(1);
            return rs;
        });
        when(sourceConn.prepareStatement(contains("fn_cdc_get_all_changes_")))
                .thenReturn(changesPs);

        // —— 元数据:dbo.t1 单列 id(PK) ——
        reader = mock(MetadataReader.class);
        TableMeta meta = new TableMeta();
        meta.setName("t1");
        meta.setSchema("dbo");
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(java.sql.Types.INTEGER);
        id.setPrimaryKey(true);
        meta.setColumns(new ArrayList<>(List.of(id)));
        meta.setPrimaryKeys(new ArrayList<>(List.of("id")));
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(meta);
        DataSourceManager dsm = mock(DataSourceManager.class);
        when(dsm.getConnection(any())).thenReturn(mock(Connection.class));

        // —— SyncContext:config/dialect/provider ——
        SyncContext ctx = mock(SyncContext.class);
        SyncConfig config = mock(SyncConfig.class);
        when(config.includesTable("t1")).thenReturn(true);
        when(config.targetTableName("t1")).thenReturn("t1");
        when(ctx.getConfig()).thenReturn(config);
        when(ctx.getSourceReader()).thenReturn(reader);
        SqlDialect dialect = mock(SqlDialect.class);
        when(dialect.getUpsertSql(any(), any(), anyList(), anyList()))
                .thenReturn("MERGE INTO t1 ...");
        // CdcEventApplier calls the 5-arg overload (typed placeholders); an unstubbed mock
        // method returns null, which then reaches prepareStatement(null).
        when(dialect.getUpsertSql(any(), any(), anyList(), anyList(), any()))
                .thenReturn("MERGE INTO t1 ...");
        when(dialect.upsertBindOrder(anyList(), anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ctx.getTargetDialect()).thenReturn(dialect);
        when(ctx.getBatchSize()).thenReturn(100);
        ConnectionProvider provider = mock(ConnectionProvider.class);
        when(provider.open(any())).thenReturn(targetConn);
        when(ctx.getConnectionProvider()).thenReturn(provider);
        when(targetConn.getAutoCommit()).thenReturn(true);

        Project project = new Project();
        project.setId(1L);
        project.setName("mssql-1");
        consumer = new SqlServerCdcConsumer(project,
                mock(SyncLockService.LockHandle.class), ctx,
                mock(CdcPositionStore.class), mock(CdcBaselineService.class),
                mock(SyncTaskStore.class), mock(SyncStateWriter.class),
                dsm, new CdcRuntimeStats(), new SyncProperties());
    }

    @Test
    void flushFailureEvictsCachedBindingSoRetryRereadsMetadata() throws Exception {
        // 目标库报「列不存在」= 源库删列后缓存 binding 构造的 SQL 必然失败。
        when(targetConn.prepareStatement(anyString()))
                .thenThrow(new SQLException("Invalid column name 'dropped_col'"));

        assertThatThrownBy(() -> consumer.drain(sourceConn, FROM_LSN))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("dropped_col");
        verify(reader, times(1)).readTable(any(), anyString(), anyString());

        // 重试(水位未动,同一批行再来):缓存若已失效,必须重读元数据重建
        // binding;旧行为 readTable 永不二次调用 → 带已删列的 SQL 重放到 strike-out。
        assertThatThrownBy(() -> consumer.drain(sourceConn, FROM_LSN))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("dropped_col");
        verify(reader, times(2)).readTable(any(), anyString(), anyString());
    }

    @Test
    void successfulFlushKeepsBindingCached() throws Exception {
        // 守护:成功路径不得逐出缓存——否则每个 drain 周期都白读一次
        // information_schema(缓存存在的意义就是省掉这次读)。
        PreparedStatement targetPs = mock(PreparedStatement.class);
        when(targetConn.prepareStatement(anyString())).thenReturn(targetPs);

        consumer.drain(sourceConn, FROM_LSN);
        consumer.drain(sourceConn, FROM_LSN);

        verify(reader, times(1)).readTable(any(), anyString(), anyString());
        verify(targetConn, times(2)).commit();
    }
}
