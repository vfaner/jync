package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
 * One table's unchecked failure must not take the rest of the cycle down with it.
 *
 * <p>Structure sync already isolates per-object failures; the data phase only isolated
 * {@code SQLException}. A mapping or dialect bug that throws a {@code RuntimeException} on
 * table k used to escape the table loop and stop every table after it, which on a
 * hundred-table database turns a one-table defect into a whole-project outage. Stop requests
 * and lease loss must still abort the cycle — they are not table failures.
 */
class SyncEngineTableFailureIsolationTest {

    private static final long PROJECT_ID = 11L;

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
    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private TableMeta first;
    private TableMeta second;
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

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:failiso_src;DB_CLOSE_DELAY=-1", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:failiso_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        when(dataSourceManager.getConnection(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetCfg)).thenReturn(targetConn);

        first = table("T1");
        second = table("T2");
        try (Statement st = targetConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T1");
            st.execute("DROP TABLE IF EXISTS T2");
            st.execute("CREATE TABLE T1 (ID BIGINT)");
            st.execute("CREATE TABLE T2 (ID BIGINT)");
        }
        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(first, second));
        when(sourceReader.readAll(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        // Structure sync is off in this fixture, so the engine asks for the reduced read.
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.estimateRowCount(any(), any(), anyString())).thenReturn(0L);
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("T1", "T2"));

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("isolation");

        when(structureSync.logType(any())).thenAnswer(inv -> inv.getArgument(0));
        when(structureSync.sortByDependency(any())).thenReturn(List.of(first, second));
        when(changeDetector.detect(eq(PROJECT_ID), any(), any())).thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString()))
                .thenAnswer(inv -> new SyncProgress());
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void anUncheckedFailureOnOneTableDoesNotStopTheNext() {
        when(dataSync.syncTable(any(), any(), argThat(t -> t != null && "T1".equals(t.getName())), any(),
                any(), any())).thenThrow(new IllegalStateException("column mapper blew up"));
        // Build the result before stubbing: stubbing a mock inside an unfinished when(...)
        // is the UnfinishedStubbingException case.
        DataSyncService.TableSyncResult secondResult = successfulTable();
        when(dataSync.syncTable(any(), any(), argThat(t -> t != null && "T2".equals(t.getName())), any(),
                any(), any())).thenReturn(secondResult);

        SyncResult result = engine.runCycle(ctx());

        verify(dataSync).syncTable(any(), any(),
                argThat(t -> t != null && "T2".equals(t.getName())), any(), any(), any());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error).contains("T1").contains("column mapper blew up"));
        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.DATA), eq("T1"),
                eq(com.qqmu.jync.model.ChangeType.ERROR), eq("column mapper blew up"),
                eq(false), anyLong());
    }

    @Test
    void aStopRequestStillAbortsTheWholeCycle() {
        when(dataSync.syncTable(any(), any(), argThat(t -> t != null && "T1".equals(t.getName())), any(),
                any(), any())).thenThrow(new StopRequestedException("stop requested"));

        engine.runCycle(ctx());

        verify(dataSync, never()).syncTable(any(), any(),
                argThat(t -> t != null && "T2".equals(t.getName())), any(), any(), any());
        // A stop is not a table failure: it must not leave an ERROR change-log row.
        verify(stateWriter, never()).recordLog(eq(PROJECT_ID), eq(ObjectType.DATA), eq("T1"),
                eq(com.qqmu.jync.model.ChangeType.ERROR), anyString(), eq(false), anyLong());
    }

    @Test
    void aLostLeaseStillAbortsTheWholeCycle() {
        when(dataSync.syncTable(any(), any(), argThat(t -> t != null && "T1".equals(t.getName())), any(),
                any(), any())).thenThrow(new LockLostException("lease lost"));

        engine.runCycle(ctx());

        verify(dataSync, never()).syncTable(any(), any(),
                argThat(t -> t != null && "T2".equals(t.getName())), any(), any(), any());
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
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }
}
