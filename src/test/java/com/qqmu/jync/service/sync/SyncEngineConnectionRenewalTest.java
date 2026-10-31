package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
 * A pooled connection that dies mid-cycle must be replaced, not shared by the rest of the cycle.
 *
 * <p>HikariCP retires a borrowed connection when a statement on it fails with a
 * {@link java.sql.SQLTimeoutException} — which is exactly what a copy that exceeds
 * {@code sync.query-timeout-seconds} produces on MySQL, "Statement cancelled due to timeout or
 * client request" — or with SQLState {@code 08}. From then on every call on that borrowed
 * handle throws {@code "Connection is closed"}.
 *
 * <p>One handle per side serves every table of the cycle. Without a replacement, one slow table
 * turns into an instant ERROR row for each table after it: the operator sees one real failure
 * of five minutes followed by a burst of identical connection errors, and has to wait for the
 * next cycle to find out which tables actually have a problem.
 */
class SyncEngineConnectionRenewalTest {

    private static final long PROJECT_ID = 12L;

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
    private Connection renewedSourceConn;
    private Connection renewedTargetConn;
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

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:renew_src;DB_CLOSE_DELAY=-1", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:renew_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        // A replacement borrow reaches the same database, only a new session: the tables the
        // retired handle worked on are still there.
        renewedSourceConn = DriverManager.getConnection(
                "jdbc:h2:mem:renew_src;DB_CLOSE_DELAY=-1", "sa", "");
        renewedTargetConn = DriverManager.getConnection(
                "jdbc:h2:mem:renew_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        when(dataSourceManager.getConnectionOrDefer(sourceCfg))
                .thenReturn(sourceConn, renewedSourceConn);
        when(dataSourceManager.getConnectionOrDefer(targetCfg))
                .thenReturn(targetConn, renewedTargetConn);

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
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.readAllForDataWithForeignKeys(any(), any(), any(), any(), any(),
                eq(false), eq(false))).thenReturn(meta);
        when(sourceReader.estimateRowCount(any(), any(), anyString())).thenReturn(0L);
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("T1", "T2"));

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("renewal");

        when(structureSync.logType(any())).thenAnswer(inv -> inv.getArgument(0));
        when(structureSync.sortByDependency(any())).thenReturn(List.of(first, second));
        when(changeDetector.detect(eq(PROJECT_ID), any(), any(), any())).thenReturn(List.of());
        when(cursorResolver.resolve(any(), any()))
                .thenReturn(CursorStrategy.identity("ID", Types.BIGINT, "test"));
        when(stateWriter.loadOrCreateProgress(eq(PROJECT_ID), anyString()))
                .thenAnswer(inv -> new SyncProgress());
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
        renewedSourceConn.close();
        renewedTargetConn.close();
    }

    @Test
    void aTableWhoseConnectionsWereRetiredDoesNotTakeTheNextTableDownWithIt() throws Exception {
        DataSyncService.TableSyncResult timedOut =
                failedTable("Statement cancelled due to timeout or client request");
        // HikariCP's behaviour after a statement timeout: the physical connection is evicted
        // and the borrowed handle answers every later call with "Connection is closed".
        when(dataSync.syncTable(same(sourceConn), same(targetConn),
                argThat(t -> t != null && "T1".equals(t.getName())), any(), any(), any()))
                .thenAnswer(inv -> {
                    closeQuietly(sourceConn);
                    closeQuietly(targetConn);
                    return timedOut;
                });
        DataSyncService.TableSyncResult ok = successfulTable();
        when(dataSync.syncTable(any(), any(),
                argThat(t -> t != null && "T2".equals(t.getName())), any(), any(), any()))
                .thenReturn(ok);

        SyncResult result = engine.runCycle(ctx());

        verify(dataSourceManager, times(2)).getConnectionOrDefer(sourceCfg);
        verify(dataSourceManager, times(2)).getConnectionOrDefer(targetCfg);
        verify(dataSync).syncTable(same(renewedSourceConn), same(renewedTargetConn),
                argThat(t -> t != null && "T2".equals(t.getName())), any(), any(), any());
        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error).contains("T1").contains("Statement cancelled"));
        assertThat(result.getErrors()).noneMatch(error -> error.contains("T2"));
        // The cycle's own try-with-resources only closes the original borrows; a replacement
        // left open would leak a seat out of the pool on every cancellation.
        assertThat(renewedSourceConn.isClosed()).isTrue();
        assertThat(renewedTargetConn.isClosed()).isTrue();
    }

    @Test
    void aFailureThatLeftTheConnectionsHealthyKeepsTheSameHandles() throws Exception {
        DataSyncService.TableSyncResult rejected =
                failedTable("Duplicate entry '1' for key 'PRIMARY'");
        when(dataSync.syncTable(any(), any(),
                argThat(t -> t != null && "T1".equals(t.getName())), any(), any(), any()))
                .thenReturn(rejected);
        DataSyncService.TableSyncResult ok = successfulTable();
        when(dataSync.syncTable(any(), any(),
                argThat(t -> t != null && "T2".equals(t.getName())), any(), any(), any()))
                .thenReturn(ok);

        engine.runCycle(ctx());

        verify(dataSourceManager, times(1)).getConnectionOrDefer(sourceCfg);
        verify(dataSourceManager, times(1)).getConnectionOrDefer(targetCfg);
        verify(dataSync).syncTable(same(sourceConn), same(targetConn),
                argThat(t -> t != null && "T2".equals(t.getName())), any(), any(), any());
    }

    @Test
    void onlyTheSideThatWasClosedIsReopened() throws Exception {
        DataSyncService.TableSyncResult failed = failedTable("connection reset");
        when(dataSync.syncTable(any(), any(),
                argThat(t -> t != null && "T1".equals(t.getName())), any(), any(), any()))
                .thenAnswer(inv -> {
                    closeQuietly(sourceConn);
                    return failed;
                });
        DataSyncService.TableSyncResult ok = successfulTable();
        when(dataSync.syncTable(any(), any(),
                argThat(t -> t != null && "T2".equals(t.getName())), any(), any(), any()))
                .thenReturn(ok);

        engine.runCycle(ctx());

        verify(dataSourceManager, times(2)).getConnectionOrDefer(sourceCfg);
        verify(dataSourceManager, times(1)).getConnectionOrDefer(targetCfg);
        verify(dataSync).syncTable(same(renewedSourceConn), same(targetConn),
                argThat(t -> t != null && "T2".equals(t.getName())), any(), any(), any());
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (Exception ignored) {
            // The point of the stub is that the handle is gone; how it went is irrelevant.
        }
    }

    private DataSyncService.TableSyncResult failedTable(String error) {
        DataSyncService.TableSyncResult result = mock(DataSyncService.TableSyncResult.class);
        when(result.isSuccess()).thenReturn(false);
        when(result.isSkipped()).thenReturn(false);
        when(result.isInitialLoad()).thenReturn(false);
        when(result.getRowsChanged()).thenReturn(0L);
        when(result.getError()).thenReturn(error);
        return result;
    }

    private DataSyncService.TableSyncResult successfulTable() {
        DataSyncService.TableSyncResult result = mock(DataSyncService.TableSyncResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.isSkipped()).thenReturn(false);
        when(result.isInitialLoad()).thenReturn(false);
        when(result.getRowsChanged()).thenReturn(0L);
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
