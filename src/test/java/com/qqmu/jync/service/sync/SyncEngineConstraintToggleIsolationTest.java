package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
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
import com.qqmu.jync.service.converter.SqlServerDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

/**
 * One table whose constraint ALTER fails must not leave the others toggled.
 *
 * <p>SQL Server's {@code NOCHECK}/{@code CHECK} is persistent table metadata, not session
 * state: whatever the re-enable pass does not reach stays as it was after this connection
 * returns to the pool. Executing the per-table script as one batch would let the driver abandon
 * every statement after the failing table, so the remaining tables would be left with foreign
 * keys silently unenforced.
 */
class SyncEngineConstraintToggleIsolationTest {

    private static final long PROJECT_ID = 12L;

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
    private final List<String> executedDdl = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean aborted;

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

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:toggle_src;DB_CLOSE_DELAY=-1", "sa", "");
        Connection h2Target = DriverManager.getConnection(
                "jdbc:h2:mem:toggle_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        targetConn = constrainedTarget(h2Target);
        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        when(dataSourceManager.getConnection(sourceCfg)).thenReturn(sourceConn);
        when(dataSourceManager.getConnection(targetCfg)).thenReturn(targetConn);

        try (Statement st = h2Target.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T1");
            st.execute("DROP TABLE IF EXISTS T2");
            st.execute("CREATE TABLE T1 (ID BIGINT)");
            st.execute("CREATE TABLE T2 (ID BIGINT)");
        }

        DatabaseMeta meta = new DatabaseMeta();
        meta.setTables(List.of(table("T1"), table("T2")));
        MetadataReader sourceReader = mock(MetadataReader.class);
        when(sourceReader.readAll(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        // Structure sync is off in this fixture, so the engine asks for the reduced read.
        when(sourceReader.readAllForData(any(), any(), any(), any(), any(), eq(false), eq(false)))
                .thenReturn(meta);
        when(sourceReader.estimateRowCount(any(), any(), anyString())).thenReturn(0L);
        MetadataReader targetReader = mock(MetadataReader.class);
        when(targetReader.listTableNames(any(), any())).thenReturn(List.of("T1", "T2"));

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("toggle");

        when(structureSync.logType(any())).thenAnswer(inv -> inv.getArgument(0));
        when(structureSync.sortByDependency(any())).thenReturn(List.of(table("T1"), table("T2")));
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
    void aFailingEnableDoesNotSkipTheRemainingTables() {
        engine.runCycle(ctx());

        assertThat(executedDdl)
                .as("T2's re-enable must run as its own statement, after T1's failed")
                .anySatisfy(sql -> assertThat(sql)
                        .contains("T2")
                        .contains("WITH CHECK CHECK CONSTRAINT ALL")
                        // A joined "T1;T2" script would match the two contains above while the
                        // driver had already abandoned the T2 half.
                        .doesNotContain("T1"));
        assertThat(aborted)
                .as("the connection must be poisoned when a table could not be re-enabled")
                .isTrue();
    }

    @Test
    void anUncheckedListingFailureStillReenablesTheConstraints() throws SQLException {
        // 目标端表清单的读取夹在"关闭约束"与"恢复约束"之间。它只声明 SQLException,
        // 但驱动/代理抛出的未检查异常会直接穿出方法——若清单读取在 try 之外,finally
        // 就永远不执行,连接带着 NOCHECK 回到池里。
        when(targetReader.listTableNames(any(), any()))
                .thenThrow(new IllegalStateException("catalog query blew up"));

        engine.runCycle(ctx());

        assertThat(executedDdl)
                .as("every toggled table must be re-enabled even when the listing threw")
                .anySatisfy(sql -> assertThat(sql)
                        .contains("T1").contains("WITH CHECK CHECK CONSTRAINT ALL"))
                .anySatisfy(sql -> assertThat(sql)
                        .contains("T2").contains("WITH CHECK CHECK CONSTRAINT ALL"));
    }

    private SyncContext ctx() {
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncData(true);
        config.setDisableTargetConstraints(true);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceReader(sourceReader)
                .targetReader(targetReader)
                .sourceDialect(new com.qqmu.jync.service.converter.GenericSqlDialect())
                .targetDialect(new SqlServerDialect())
                .batchSize(50)
                .build();
    }

    /**
     * Delegating connection whose statements record DDL and reject T1's re-enable, and whose
     * {@code abort} is observable instead of actually killing the H2 connection.
     */
    private Connection constrainedTarget(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if ("abort".equals(method.getName())) {
                        aborted = true;
                        return null;
                    }
                    if ("createStatement".equals(method.getName())) {
                        return recordingStatement(delegate.createStatement());
                    }
                    return method.invoke(delegate, args);
                });
    }

    private Statement recordingStatement(Statement delegate) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class}, (proxy, method, args) -> {
                    if ("execute".equals(method.getName()) && args != null && args.length == 1) {
                        String sql = (String) args[0];
                        if (sql.startsWith("ALTER TABLE")) {
                            // SQL Server syntax on an H2 target: record it and stop there.
                            executedDdl.add(sql);
                            if (sql.contains("WITH CHECK") && sql.contains("T1")) {
                                throw new SQLException("constraint state rejected for T1");
                            }
                            return false;
                        }
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
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
