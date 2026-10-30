package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.cdc.LogCapture;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.GenericMetadataReader;
import com.qqmu.jync.service.monitor.ChangeDetector;
import com.qqmu.jync.service.monitor.CursorStrategyResolver;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Deletion detection compares both key sets in memory, so a table over the row cap is skipped.
 * That makes "同步删除" silently do nothing for every large table — the operator has to be
 * told once, at WARN, and the skip has to leave a record in the change log; repeating it every
 * poll would bury the log.
 *
 * <p>The cycle's behaviour is unchanged: the skipped comparison is exactly what used to happen.
 */
class SyncEngineDeletionSkipWarningTest {

    private static final String SOURCE_URL = "jdbc:h2:mem:del_warn_src;DB_CLOSE_DELAY=-1";
    private static final String TARGET_URL = "jdbc:h2:mem:del_warn_tgt;DB_CLOSE_DELAY=-1";

    private Connection source;
    private Connection target;
    private DataSourceManager dataSourceManager;
    private SyncStateWriter stateWriter;
    private SyncEngine engine;
    private SyncProperties properties;
    private DatabaseConfig sourceCfg;
    private DatabaseConfig targetCfg;
    private Project project;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection(SOURCE_URL, "sa", "");
        target = DriverManager.getConnection(TARGET_URL, "sa", "");
        for (Connection c : List.of(source, target)) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS BIG (id BIGINT PRIMARY KEY, name VARCHAR(32))");
            }
        }
        try (Statement st = source.createStatement()) {
            st.execute("MERGE INTO BIG (id, name) VALUES (1, 'a')");
        }
        // The row the source no longer has: detecting it is exactly what the cap disables.
        try (Statement st = target.createStatement()) {
            st.execute("MERGE INTO BIG (id, name) VALUES (2, 'gone')");
        }

        properties = new SyncProperties();
        properties.setFullCompareMaxRows(0L);

        dataSourceManager = mock(DataSourceManager.class);
        stateWriter = mock(SyncStateWriter.class);
        StructureSyncService structureSync = mock(StructureSyncService.class);
        engine = new SyncEngine(dataSourceManager, mock(ChangeDetector.class),
                new CursorStrategyResolver(properties), structureSync,
                new DataSyncService(properties), stateWriter, properties);

        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        // A fresh handle per call: runCycle closes what it borrows, and this test drives
        // more than one cycle through the same mocked manager.
        when(dataSourceManager.getConnectionOrDefer(sourceCfg)).thenAnswer(
                inv -> DriverManager.getConnection(SOURCE_URL, "sa", ""));
        when(dataSourceManager.getConnectionOrDefer(targetCfg)).thenAnswer(
                inv -> DriverManager.getConnection(TARGET_URL, "sa", ""));
        when(structureSync.sortByDependency(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stateWriter.loadOrCreateProgress(anyLong(), anyString()))
                .thenReturn(new SyncProgress());

        project = new Project();
        project.setId(11L);
        project.setName("del-warn");
    }

    @AfterEach
    void tearDown() {
        for (String url : List.of(SOURCE_URL, TARGET_URL)) {
            try (Connection c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                st.execute("DROP ALL OBJECTS");
            } catch (SQLException ignored) {
                // The engine closes the handles it borrows; a failed test may already have.
            }
        }
    }

    @Test
    void anOversizedTableWhoseDeletesCannotBeDetectedIsNamedOnce() throws SQLException {
        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncEngine.class);
        try {
            engine.runCycle(ctx());
            engine.runCycle(ctx());
        } finally {
            LogCapture.detach(SyncEngine.class, logs);
        }

        List<String> warnings = LogCapture.messagesAt(Level.WARN, logs).stream()
                .filter(message -> message.contains("BIG"))
                .filter(message -> message.contains("Deletion sync"))
                .toList();
        assertThat(warnings)
                .as("the operator must learn that deletes do not propagate, without a warning per poll")
                .hasSize(1);

        // The same note once in the change log, so it is visible without grepping the log file.
        verify(stateWriter, times(1)).recordLog(eq(11L), eq(ObjectType.DATA), eq("BIG"),
                eq(ChangeType.INFO), argThat(message -> message != null
                        && message.contains("Deletion sync")),
                eq(true), eq(0L));

        // The skip is the pre-existing behaviour: the stale target row stays.
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM BIG")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    /**
     * The one-warning rule is per project, not per process. Resetting or deleting a project
     * drops its cached entries so the next run starts clean; a leftover "already warned" entry
     * would silence the note for a table nobody has been told about since the reset.
     */
    @Test
    void evictingTheProjectLetsTheSkipBeReportedAgain() throws SQLException {
        engine.runCycle(ctx());
        engine.runCycle(ctx());
        engine.evictProjectCaches(11L);

        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncEngine.class);
        try {
            engine.runCycle(ctx());
        } finally {
            LogCapture.detach(SyncEngine.class, logs);
        }

        assertThat(LogCapture.messagesAt(Level.WARN, logs))
                .as("an evicted project must not keep a stale throttle entry")
                .anyMatch(message -> message.contains("BIG")
                        && message.contains("Deletion sync"));
    }

    private SyncContext ctx() {
        SyncConfig config = new SyncConfig();
        config.getTables().add("BIG");
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncData(true);
        config.setSyncDeletes(true);
        config.setDisableTargetConstraints(false);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceReader(new GenericMetadataReader())
                .targetReader(new GenericMetadataReader())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }
}
