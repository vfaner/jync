package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.TableContentAudit;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;
import com.qqmu.jync.service.task.SyncContextFactory;

/**
 * The manual audit entry point: one table, the existing progress row, no writes.
 *
 * <p>The runner is the path behind the detail page's button, so what matters is that it
 * reads exactly the requested table, refuses a table that was never synced instead of
 * inventing a progress row, and leaves the comparison itself to {@link ContentAuditService}.
 */
class ContentAuditRunnerTest {

    private static final long PROJECT_ID = 7L;
    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private ProjectRepository projectRepository;
    private SyncProgressRepository progressRepository;
    private SyncContextFactory contextFactory;
    private DataSourceManager dataSourceManager;
    private CursorStrategyResolver cursorResolver;
    private ContentAuditService contentAudit;
    private MetadataReader sourceReader;
    private ContentAuditRunner runner;
    private Connection sourceConn;
    private Connection targetConn;
    private Project project;
    private SyncContext ctx;
    private TableMeta orders;
    private SyncProgress progress;

    @BeforeEach
    void setUp() throws SQLException {
        String tag = UUID.randomUUID().toString().replace("-", "");
        projectRepository = mock(ProjectRepository.class);
        progressRepository = mock(SyncProgressRepository.class);
        contextFactory = mock(SyncContextFactory.class);
        dataSourceManager = mock(DataSourceManager.class);
        cursorResolver = mock(CursorStrategyResolver.class);
        contentAudit = mock(ContentAuditService.class);
        sourceReader = mock(MetadataReader.class);
        runner = new ContentAuditRunner(projectRepository, progressRepository, contextFactory,
                dataSourceManager, cursorResolver, contentAudit);

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:ca_runner_src_" + tag, "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:ca_runner_tgt_" + tag, "sa", "");
        try (Statement st = sourceConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, V VARCHAR(20))");
        }
        try (Statement st = targetConn.createStatement()) {
            st.execute("CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, V VARCHAR(20))");
        }

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("orders-sync");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        orders = tableMeta("ORDERS",
                column("ID", Types.BIGINT, "BIGINT"),
                column("V", Types.VARCHAR, "VARCHAR"));
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceConfig(new DatabaseConfig())
                .targetConfig(new DatabaseConfig())
                .sourceType(DatabaseType.H2)
                .targetType(DatabaseType.H2)
                                .sourceReader(sourceReader)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
        when(contextFactory.build(project, "content-audit", null)).thenReturn(ctx);
        when(dataSourceManager.getConnectionOrDefer(ctx.getSourceConfig())).thenReturn(sourceConn);
        when(dataSourceManager.getConnectionOrDefer(ctx.getTargetConfig())).thenReturn(targetConn);
        when(sourceReader.readTable(sourceConn, null, "ORDERS")).thenReturn(orders);
        when(cursorResolver.resolve(orders, ctx.getConfig())).thenReturn(IDENTITY);

        progress = new SyncProgress();
        progress.setProjectId(PROJECT_ID);
        progress.setObjectType(ObjectType.TABLE);
        progress.setObjectName("ORDERS");
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("2");
    }

    @AfterEach
    void tearDown() throws SQLException {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void auditProjectReadsOnlyRequestedTable() throws SQLException {
        when(progressRepository.findByProjectIdAndObjectTypeAndObjectName(
                PROJECT_ID, ObjectType.TABLE, "ORDERS")).thenReturn(Optional.of(progress));
        TableContentAudit expected = ok();
        when(contentAudit.auditNow(sourceConn, targetConn, orders, IDENTITY, progress, ctx))
                .thenReturn(expected);

        TableContentAudit result = runner.auditProject(PROJECT_ID, "ORDERS");

        assertThat(result).isSameAs(expected);
        verify(sourceReader, times(1)).readTable(sourceConn, null, "ORDERS");
        verifyNoMoreInteractions(sourceReader);
        verify(contentAudit).auditNow(sourceConn, targetConn, orders, IDENTITY, progress, ctx);
        verify(contentAudit).record(ctx, expected);
    }

    @Test
    void auditProjectReturnsSkippedWhenNeverSynced() {
        when(progressRepository.findByProjectIdAndObjectTypeAndObjectName(
                PROJECT_ID, ObjectType.TABLE, "ORDERS")).thenReturn(Optional.empty());

        TableContentAudit result = runner.auditProject(PROJECT_ID, "ORDERS");

        assertThat(result.status()).isEqualTo(TableContentAudit.Status.SKIPPED);
        assertThat(result.table()).isEqualTo("ORDERS");
        assertThat(result.detail()).isEqualTo("never-synced");
        verifyNoInteractions(contextFactory, dataSourceManager, contentAudit, sourceReader);
    }

    @Test
    void auditProjectDoesNotCreateProgressRow() {
        when(progressRepository.findByProjectIdAndObjectTypeAndObjectName(
                PROJECT_ID, ObjectType.TABLE, "ORDERS")).thenReturn(Optional.of(progress));
        when(contentAudit.auditNow(any(), any(), any(), any(), any(), any())).thenReturn(ok());

        runner.auditProject(PROJECT_ID, "ORDERS");

        verify(progressRepository, times(1))
                .findByProjectIdAndObjectTypeAndObjectName(
                        PROJECT_ID, ObjectType.TABLE, "ORDERS");
        verify(progressRepository, never()).save(any(SyncProgress.class));
        verify(progressRepository, never()).saveAll(any());
    }

    @Test
    void auditProjectAppliesTargetTableMapping() throws SQLException {
        // The mapping lives in the project's sync config and is honoured by the comparison
        // itself; a runner that passed the source name to the target side would report
        // "target-missing" instead of comparing ORDERS with ORDERS_V2.
        Connection closedSource = DriverManager.getConnection(
                "jdbc:h2:mem:ca_runner_map_src_" + UUID.randomUUID().toString().replace("-", ""),
                "sa", "");
        Connection closedTarget = DriverManager.getConnection(
                "jdbc:h2:mem:ca_runner_map_tgt_" + UUID.randomUUID().toString().replace("-", ""),
                "sa", "");
        try {
            exec(closedSource, "CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, V VARCHAR(20))");
            exec(closedTarget, "CREATE TABLE ORDERS_V2 (ID BIGINT PRIMARY KEY, V VARCHAR(20))");
            exec(closedSource, "INSERT INTO ORDERS VALUES (1, 'a'), (2, 'b')");
            exec(closedTarget, "INSERT INTO ORDERS_V2 VALUES (1, 'a'), (2, 'b')");
            when(dataSourceManager.getConnectionOrDefer(ctx.getSourceConfig()))
                    .thenReturn(closedSource);
            when(dataSourceManager.getConnectionOrDefer(ctx.getTargetConfig()))
                    .thenReturn(closedTarget);
            when(progressRepository.findByProjectIdAndObjectTypeAndObjectName(
                    PROJECT_ID, ObjectType.TABLE, "ORDERS")).thenReturn(Optional.of(progress));
            when(sourceReader.readTable(closedSource, null, "ORDERS")).thenReturn(orders);
            ctx.getConfig().setTableNameMapping(Map.of("ORDERS", "ORDERS_V2"));

            SyncProperties properties = new SyncProperties();
            ContentAuditService realAudit = new ContentAuditService(
                    new DataSyncService(properties), mock(SyncStateWriter.class), properties);
            ContentAuditRunner mappedRunner = new ContentAuditRunner(projectRepository,
                    progressRepository, contextFactory, dataSourceManager, cursorResolver,
                    realAudit);

            TableContentAudit result = mappedRunner.auditProject(PROJECT_ID, "ORDERS");

            assertThat(result.status()).isEqualTo(TableContentAudit.Status.OK);
            assertThat(result.sampled()).isEqualTo(2);
            assertThat(result.mismatched()).isZero();
        } finally {
            closedSource.close();
            closedTarget.close();
        }
    }

    @Test
    void auditProjectReportsAnUnexpectedReaderFailureInsteadOfThrowing() throws SQLException {
        when(progressRepository.findByProjectIdAndObjectTypeAndObjectName(
                PROJECT_ID, ObjectType.TABLE, "ORDERS")).thenReturn(Optional.of(progress));
        when(sourceReader.readTable(sourceConn, null, "ORDERS"))
                .thenThrow(new IllegalStateException("metadata unavailable"));

        TableContentAudit result = runner.auditProject(PROJECT_ID, "ORDERS");

        assertThat(result.status()).isEqualTo(TableContentAudit.Status.ERROR);
        assertThat(result.table()).isEqualTo("ORDERS");
        assertThat(result.detail()).contains("metadata unavailable");
    }

    private static TableContentAudit ok() {
        return new TableContentAudit("ORDERS", TableContentAudit.Status.OK, 2, 2, 0, 0, 0,
                List.of(), null, 3L);
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static TableMeta tableMeta(String name, ColumnMeta... columns) {
        TableMeta table = new TableMeta();
        table.setName(name);
        table.setColumns(List.of(columns));
        table.setPrimaryKeys(List.of("ID"));
        return table;
    }

    private static ColumnMeta column(String name, int jdbcType, String typeName) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setTypeName(typeName);
        return column;
    }
}
