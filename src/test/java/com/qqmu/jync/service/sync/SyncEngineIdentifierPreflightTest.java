package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.DatabaseMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * A table the target cannot name is reported once and left alone by the rest of the cycle.
 *
 * <p>Without the preflight, the 31-byte table reaches the target and fails there with a driver
 * error that names neither the mapping nor the limit — and in the structure phase it can leave
 * a half-applied batch. The healthy table next to it must still sync.
 */
class SyncEngineIdentifierPreflightTest {

    private static final long PROJECT_ID = 21L;
    private static final String TOO_LONG = "T".repeat(31);

    private DataSourceManager dataSourceManager;
    private ChangeDetector changeDetector;
    private CursorStrategyResolver cursorResolver;
    private StructureSyncService structureSync;
    private DataSyncService dataSync;
    private SyncStateWriter stateWriter;
    private SyncEngine engine;
    private Connection sourceConn;
    private Connection targetConn;
    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        dataSourceManager = mock(DataSourceManager.class);
        changeDetector = mock(ChangeDetector.class);
        cursorResolver = mock(CursorStrategyResolver.class);
        structureSync = mock(StructureSyncService.class);
        dataSync = mock(DataSyncService.class);
        stateWriter = mock(SyncStateWriter.class);
        engine = new SyncEngine(dataSourceManager, changeDetector, cursorResolver, structureSync,
                dataSync, stateWriter, new SyncProperties());

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:preflight_src;DB_CLOSE_DELAY=-1", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:preflight_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        when(dataSourceManager.getConnection(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetCfg)).thenReturn(targetConn);

        try (java.sql.Statement st = targetConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT)");
        }

        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(table(TOO_LONG), table("ORDERS")));
        MetadataReader sourceReader = mock(MetadataReader.class);
        when(sourceReader.readAll(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        // Structure sync is off in this fixture, so the engine asks for the reduced read.
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.estimateRowCount(any(), any(), anyString())).thenReturn(0L);
        MetadataReader targetReader = mock(MetadataReader.class);
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS"));

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("preflight");

        when(structureSync.logType(any())).thenAnswer(inv -> inv.getArgument(0));
        when(structureSync.sortByDependency(any())).thenAnswer(inv -> inv.getArgument(0));
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString()))
                .thenAnswer(inv -> new SyncProgress());
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> successfulTable());

        this.sourceReader = sourceReader;
        this.targetReader = targetReader;
    }

    private MetadataReader sourceReader;
    private MetadataReader targetReader;

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void theOffendingTableIsReportedAndSkippedWhileTheRestSyncs() {
        SyncResult result = engine.runCycle(ctx());

        assertThat(result.getErrors()).anySatisfy(error -> assertThat(error)
                .contains(TOO_LONG).contains("30"));
        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.TABLE), eq(TOO_LONG),
                eq(ChangeType.ERROR), argThat(details -> details != null && details.contains("30")),
                eq(false), eq(0L));
        verify(dataSync, never()).syncTable(any(), any(),
                argThat(t -> t != null && TOO_LONG.equals(t.getName())), any(), any(), any());
        verify(dataSync).syncTable(any(), any(),
                argThat(t -> t != null && "ORDERS".equals(t.getName())), any(), any(), any());
    }

    private SyncContext ctx() {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncData(true);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.MYSQL)
                .targetType(DatabaseType.ORACLE)
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(new GenericSqlDialect())
                // 11g: the legacy 30-byte identifier limit.
                .targetDialect(new OracleDialect(11))
                .batchSize(50)
                .build();
    }

    private DataSyncService.TableSyncResult successfulTable() {
        DataSyncService.TableSyncResult result = mock(DataSyncService.TableSyncResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.isSkipped()).thenReturn(false);
        when(result.isInitialLoad()).thenReturn(false);
        when(result.getRowsChanged()).thenReturn(0);
        return result;
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
