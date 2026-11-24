package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

class SyncEngineTableConcurrencyTest {

    private static final long PROJECT_ID = 91L;
    private static final String SRC = "jdbc:h2:mem:table_conc_src;DB_CLOSE_DELAY=-1";
    private static final String TGT = "jdbc:h2:mem:table_conc_tgt;DB_CLOSE_DELAY=-1";

    private Connection sourceConn;
    private Connection targetConn;
    private DataSyncService dataSync;
    private SyncEngine engine;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        sourceConn = DriverManager.getConnection(SRC, "sa", "");
        targetConn = DriverManager.getConnection(TGT, "sa", "");
        try (Statement st = targetConn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE TABLE A (ID BIGINT PRIMARY KEY)");
            st.execute("CREATE TABLE B (ID BIGINT PRIMARY KEY)");
        }

        DatabaseConfig sourceCfg = new DatabaseConfig();
        DatabaseConfig targetCfg = new DatabaseConfig();
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("table-concurrency");

        MetadataReader sourceReader = mock(MetadataReader.class);
        MetadataReader targetReader = mock(MetadataReader.class);
        TableMeta a = table("A");
        TableMeta b = table("B");
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(a, b));
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(false), eq(false))).thenReturn(meta);
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("A", "B"));

        SyncConfig config = new SyncConfig();
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncData(true);
        config.setDisableTargetConstraints(false);
        ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.MYSQL)
                .targetType(DatabaseType.MYSQL)
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(new MySqlDialect())
                .targetDialect(new MySqlDialect())
                .batchSize(10)
                .connectionProvider(cfg -> DriverManager.getConnection(
                        cfg == sourceCfg ? SRC : TGT, "sa", ""))
                .build();

        ChangeDetector changeDetector = mock(ChangeDetector.class);
        CursorStrategyResolver cursorResolver = mock(CursorStrategyResolver.class);
        StructureSyncService structureSync = mock(StructureSyncService.class);
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any()))
                .thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        when(structureSync.dependencyLevels(any()))
                .thenReturn(List.of(List.of(a, b)));
        when(structureSync.hasPendingIndexes(anyLong(), anyString())).thenReturn(false);
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString()))
                .thenAnswer(inv -> new SyncProgress());

        dataSync = mock(DataSyncService.class);
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> successfulTable());

        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnectionOrDefer(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnectionOrDefer(targetCfg)).thenReturn(targetConn);
        SyncProperties properties = new SyncProperties();
        properties.setMaxConcurrentTables(2);
        properties.setMaxPoolSize(10);
        properties.setFullLoadParallelism(1);
        engine = new SyncEngine(dataSourceManager, changeDetector, cursorResolver,
                structureSync, dataSync, stateWriter, properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void twoIndependentTablesOverlapOnlyWhenConcurrencyIsEnabled() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            bothStarted.countDown();
            try {
                bothStarted.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return successfulTable();
        });

        engine.runCycle(ctx);

        assertThat(peak.get()).isGreaterThanOrEqualTo(2);
    }

    private static TableMeta table(String name) {
        TableMeta table = new TableMeta();
        table.setName(name);
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        table.getColumns().add(id);
        table.getPrimaryKeys().add("ID");
        return table;
    }

    private static DataSyncService.TableSyncResult successfulTable() {
        DataSyncService.TableSyncResult result = mock(DataSyncService.TableSyncResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.isSkipped()).thenReturn(false);
        when(result.isInitialLoad()).thenReturn(false);
        return result;
    }
}
