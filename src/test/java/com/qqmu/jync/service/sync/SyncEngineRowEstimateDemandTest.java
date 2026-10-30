package com.qqmu.jync.service.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * 估算行数是一次源库目录往返，而它每个轮询周期、每张表只被问一次。
 *
 * <p>真正会读这个数字的只有三条路径：删除检测（按表大小决定要不要扫主键集）、显式
 * FULL_COMPARE（每轮重读整表的告警与横幅）、以及自动选策略时兜底的“这张表够小吗”。
 * 前两条针对全项目，值得每轮预取；第三条只对“没有任何游标候选”的那张表有意义，别的表
 * 永远用不到 —— 两千张表、跨广域 20ms 往返的库，每周期白等四十秒就是这么来的。
 */
class SyncEngineRowEstimateDemandTest {

    private static final long PROJECT_ID = 42L;

    private DataSourceManager dataSourceManager;
    private ChangeDetector changeDetector;
    private CursorStrategyResolver cursorResolver;
    private StructureSyncService structureSync;
    private DataSyncService dataSync;
    private SyncStateWriter stateWriter;
    private MetadataReader sourceReader;
    private MetadataReader targetReader;
    private SyncEngine engine;
    private Connection sourceConn;
    private Connection targetConn;
    private final DatabaseConfig sourceCfg = new DatabaseConfig();
    private final DatabaseConfig targetCfg = new DatabaseConfig();
    private Project project;
    private SyncProgress progress;

    @BeforeEach
    void setUp() throws Exception {
        dataSourceManager = mock(DataSourceManager.class);
        changeDetector = mock(ChangeDetector.class);
        cursorResolver = mock(CursorStrategyResolver.class);
        structureSync = mock(StructureSyncService.class);
        dataSync = mock(DataSyncService.class);
        stateWriter = mock(SyncStateWriter.class);
        sourceReader = mock(MetadataReader.class);
        targetReader = mock(MetadataReader.class);
        engine = new SyncEngine(dataSourceManager, changeDetector, cursorResolver, structureSync,
                dataSync, stateWriter, new SyncProperties());

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:row_estimate_src", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:row_estimate_tgt", "sa", "");
        when(dataSourceManager.getConnectionOrDefer(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnectionOrDefer(targetCfg)).thenReturn(targetConn);

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("row-estimate-demand");

        TableMeta orders = table("ORDERS");
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(orders));
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(false), eq(false))).thenReturn(meta);

        when(targetReader.listTableNames(any(), any())).thenReturn(List.of());
        when(structureSync.sortByDependency(any())).thenReturn(List.of(orders));
        when(structureSync.hasPendingIndexes(PROJECT_ID, "ORDERS")).thenReturn(false);
        when(structureSync.apply(any(), any(), any()))
                .thenReturn(StructureSyncService.ApplyOutcome.ok("Created table ORDERS"));
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of());

        progress = new SyncProgress();
        progress.setProjectId(PROJECT_ID);
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("ORDERS");
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("42");
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString())).thenReturn(progress);
        when(stateWriter.forceReload(progress)).thenAnswer(inv -> {
            SyncProgress cleared = inv.getArgument(0);
            cleared.setLastSyncValue(null);
            cleared.setInitialLoadDone(false);
            return cleared;
        });

        DataSyncService.TableSyncResult result = mock(DataSyncService.TableSyncResult.class);
        when(result.isInitialLoad()).thenReturn(false);
        when(result.isSuccess()).thenReturn(true);
        when(result.isSkipped()).thenReturn(false);
        when(result.getRowsChanged()).thenReturn(0L);
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(result);
        when(dataSync.syncDeletions(any(), any(), any(), any())).thenReturn(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    /**
     * 游标列选好、删除检测关掉的项目：这张表的估算没人会读，一趟目录往返都不该发生。
     */
    @Test
    void aCursorDrivenTableCostsNoRowEstimatePerCycle() throws Exception {
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));

        engine.runCycle(ctx(false, false, CaptureStrategy.POLLING));

        verify(sourceReader, never()).estimateRowCount(any(), any(), any());
        verify(dataSync).syncTable(any(), any(), any(), any(), any(), any());
    }

    /**
     * 兜底判断依赖的正是这个数字：没有任何游标候选时，取一次估算再让解析器决定。
     * 少了这一步，小表会被判成“大小未知、不能全量对比”，从此只加载一次就不再跟进。
     */
    @Test
    void aTableWithNoCursorCandidateGetsItsEstimateFetchedBeforeTheFallbackVerdict()
            throws Exception {
        when(sourceReader.estimateRowCount(any(), any(), any())).thenReturn(500L);
        when(cursorResolver.resolve(any(), any())).thenAnswer(inv -> {
            TableMeta table = inv.getArgument(0);
            Long estimate = table.getApproximateRowCount();
            return estimate != null && estimate <= 20_000L
                    ? CursorStrategy.fullCompare("small enough for full comparison")
                    : CursorStrategy.none("no cursor candidate and the size is unknown");
        });

        engine.runCycle(ctx(false, false, CaptureStrategy.POLLING));

        verify(sourceReader, times(1)).estimateRowCount(any(), any(), any());
        ArgumentCaptor<CursorStrategy> resolved = ArgumentCaptor.forClass(CursorStrategy.class);
        verify(dataSync).syncTable(any(), any(), any(), resolved.capture(), any(), any());
        assertEquals(CursorStrategy.Kind.FULL_COMPARE, resolved.getValue().getKind(),
                "the fallback must see the table's size, or a small table is never re-read");
    }

    /**
     * 删除检测按表大小决定要不要扫主键集，所以它需要每一张表的数字 —— 这条路径上的
     * 预取是有用的，不能一起省掉。
     */
    @Test
    void deletionDetectionStillGetsAnEstimateForEveryTable() throws Exception {
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));

        engine.runCycle(ctx(false, true, CaptureStrategy.POLLING));

        verify(sourceReader, times(1)).estimateRowCount(any(), any(), any());
    }

    /**
     * 显式 FULL_COMPARE 的告警和项目页横幅都从快照里的估算读数，同样要每轮预取。
     */
    @Test
    void anExplicitFullCompareProjectStillGetsAnEstimateForEveryTable() throws Exception {
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.fullCompare("configured"));

        engine.runCycle(ctx(false, false, CaptureStrategy.FULL_COMPARE));

        verify(sourceReader, times(1)).estimateRowCount(any(), any(), any());
    }

    private SyncContext ctx(boolean syncStructure, boolean syncDeletes,
                            CaptureStrategy captureStrategy) {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(syncStructure);
        config.setSyncData(true);
        config.setSyncDeletes(syncDeletes);
        config.setCaptureStrategy(captureStrategy);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.MYSQL)
                .targetType(DatabaseType.MYSQL)
                .sourceSchema("APP")
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .deferSecondaryIndexes(true)
                .build();
    }

    private static TableMeta table(String name) {
        TableMeta table = new TableMeta();
        table.setName(name);
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        table.setColumns(List.of(id));
        return table;
    }
}
