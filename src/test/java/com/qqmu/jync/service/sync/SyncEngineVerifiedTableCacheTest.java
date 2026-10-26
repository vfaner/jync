package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * A cached "this table's shape is fine" verdict must not outlive the evidence for it.
 *
 * <p>The shape check costs one {@code DatabaseMetaData.getColumns} per table, so its verdict is
 * cached for the process lifetime. That is only sound while the target keeps the shape it was
 * verified with. An administrator migration that drops a column after the check invalidates the
 * verdict silently: without a way back into the check, the table fails on the same JDBC error
 * every cycle and the missing-column repair (the whole point of the check when structure sync is
 * on) is never attempted again until the process restarts or the project is reset.
 */
class SyncEngineVerifiedTableCacheTest {

    private static final long PROJECT_ID = 21L;
    private static final String SOURCE_URL = "jdbc:h2:mem:verifiedcache_src;DB_CLOSE_DELAY=-1";
    private static final String TARGET_URL = "jdbc:h2:mem:verifiedcache_tgt;DB_CLOSE_DELAY=-1";

    private DataSourceManager dataSourceManager;
    private ChangeDetector changeDetector;
    private CursorStrategyResolver cursorResolver;
    private StructureSyncService structureSync;
    private DataSyncService dataSync;
    private SyncStateWriter stateWriter;
    private MetadataReader sourceReader;
    private MetadataReader targetReader;
    private SyncEngine engine;
    private Connection adminConn;
    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private TableMeta sourceTable;
    private Project project;

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

        // A fresh handle per call: the engine closes what it opens at the end of each cycle,
        // and the database itself stays alive thanks to DB_CLOSE_DELAY=-1.
        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        when(dataSourceManager.getConnection(sourceCfg))
                .thenAnswer(inv -> DriverManager.getConnection(SOURCE_URL, "sa", ""));
        when(dataSourceManager.getConnection(targetCfg))
                .thenAnswer(inv -> DriverManager.getConnection(TARGET_URL, "sa", ""));

        adminConn = DriverManager.getConnection(TARGET_URL, "sa", "");
        try (Statement st = adminConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T1");
            st.execute("CREATE TABLE T1 (ID BIGINT, C1 VARCHAR(20))");
        }

        sourceTable = new TableMeta();
        sourceTable.setName("T1");
        sourceTable.setColumns(List.of(column("ID", Types.BIGINT), column("C1", Types.VARCHAR)));
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(sourceTable));
        when(sourceReader.readAll(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(false), eq(false))).thenReturn(meta);
        when(sourceReader.estimateRowCount(any(), any(), anyString())).thenReturn(0L);
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("T1"));

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("verified-cache");

        when(structureSync.logType(any())).thenAnswer(inv -> inv.getArgument(0));
        when(structureSync.sortByDependency(any())).thenReturn(List.of(sourceTable));
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString()))
                .thenAnswer(inv -> new SyncProgress());
    }

    @AfterEach
    void tearDown() throws Exception {
        adminConn.close();
    }

    @Test
    void aFailedDataSyncEvictsTheCachedShapeVerdict() throws Exception {
        // Cycle 1 — the target is compatible; the check passes and caches its verdict.
        // Build the result before stubbing: stubbing a mock inside an unfinished when(...)
        // is the UnfinishedStubbingException case.
        DataSyncService.TableSyncResult firstResult = successfulTable();
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any()))
                .thenReturn(firstResult);
        assertThat(engine.runCycle(ctx()).isSuccess()).isTrue();

        // The target is altered behind Jync's back after we stopped checking it.
        try (Statement st = adminConn.createStatement()) {
            st.execute("ALTER TABLE T1 DROP COLUMN C1");
        }

        // Cycle 2 — the cached verdict skips the check, so the data phase runs and hits the
        // missing column (mocked here: the driver-level failure is not what this test pins).
        DataSyncService.TableSyncResult failed = mock(DataSyncService.TableSyncResult.class);
        when(failed.isSuccess()).thenReturn(false);
        when(failed.getError()).thenReturn("Column \"C1\" not found");
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(failed);
        assertThat(engine.runCycle(ctx()).isSuccess()).isFalse();

        // Cycle 3 — the failure must have dropped the stale verdict, so the shape check runs
        // again, sees the missing column, and blocks the data phase with an actionable error
        // instead of retrying the same doomed write forever.
        clearInvocations(dataSync, stateWriter);
        SyncResult third = engine.runCycle(ctx());

        assertThat(third.isSuccess()).isFalse();
        assertThat(third.getErrors()).anySatisfy(error ->
                assertThat(error).contains("missing column").contains("C1"));
        verify(dataSync, never()).syncTable(any(), any(), any(), any(), any(), any());
        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(com.qqmu.jync.model.ObjectType.TABLE),
                eq("T1"), eq(com.qqmu.jync.model.ChangeType.ERROR),
                argThat(reason -> reason.contains("missing column")), eq(false), anyLong());
    }

    @Test
    void aVerdictForOneTargetSchemaDoesNotVouchForTheSameTableNameInAnother() throws Exception {
        // Cycle 1: PUBLIC.T1 matches the source; the verdict is cached for this schema.
        DataSyncService.TableSyncResult ok = successfulTable();
        when(dataSync.syncTable(any(), any(), any(), any(), any(), any())).thenReturn(ok);
        assertThat(engine.runCycle(ctx("PUBLIC")).isSuccess()).isTrue();

        // The same table name, another schema, a shape that cannot receive the source rows:
        // a table name alone is not a target table, so "T1 was verified" must not carry over.
        try (Statement st = adminConn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS S2");
            st.execute("DROP TABLE IF EXISTS S2.T1");
            st.execute("CREATE TABLE S2.T1 (ID BIGINT)");
        }

        clearInvocations(dataSync, stateWriter);
        SyncResult second = engine.runCycle(ctx("S2"));

        assertThat(second.isSuccess()).isFalse();
        assertThat(second.getErrors()).anySatisfy(error ->
                assertThat(error).contains("missing column").contains("C1"));
        verify(dataSync, never()).syncTable(any(), any(), any(), any(), any(), any());
    }

    private DataSyncService.TableSyncResult successfulTable() {
        DataSyncService.TableSyncResult result = mock(DataSyncService.TableSyncResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.isSkipped()).thenReturn(false);
        when(result.isInitialLoad()).thenReturn(false);
        when(result.getRowsChanged()).thenReturn(0);
        return result;
    }

    private static ColumnMeta column(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
    }

    private SyncContext ctx() {
        return ctx(null);
    }

    private SyncContext ctx(String targetSchema) {
        SyncConfig config = new SyncConfig();
        // Structure sync off: the repair path is not under test here, only that the check
        // runs again and reports the blocker. It is the same code path either way.
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncData(true);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .targetSchema(targetSchema)
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }
}
