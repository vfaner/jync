package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
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
 * An explicitly selected FULL_COMPARE project whose tables exceed the row cap must say so
 * once, at WARN, when the cycle starts — without changing what the cycle does.
 *
 * <p>The page banner covers the operator looking at the detail view; this warning covers
 * every other path into the same configuration (a config file, an API save, a project that
 * started before the page was opened). Emitting it every poll would bury the log, so the
 * engine remembers which project/table pairs it has already named.
 */
class SyncEngineFullCompareWarningTest {

    private static final String SOURCE_URL = "jdbc:h2:mem:fc_warn_src;DB_CLOSE_DELAY=-1";
    private static final String TARGET_URL = "jdbc:h2:mem:fc_warn_tgt;DB_CLOSE_DELAY=-1";

    private Connection source;
    private Connection target;
    private DataSourceManager dataSourceManager;
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

        properties = new SyncProperties();

        dataSourceManager = mock(DataSourceManager.class);
        SyncStateWriter stateWriter = mock(SyncStateWriter.class);
        StructureSyncService structureSync = mock(StructureSyncService.class);
        engine = new SyncEngine(dataSourceManager, mock(ChangeDetector.class),
                new CursorStrategyResolver(properties), structureSync,
                new DataSyncService(properties), stateWriter, properties);

        sourceCfg = new DatabaseConfig();
        targetCfg = new DatabaseConfig();
        // A fresh handle per call: runCycle closes what it borrows, and this test drives
        // more than one cycle through the same mocked manager.
        when(dataSourceManager.getConnection(sourceCfg)).thenAnswer(
                inv -> DriverManager.getConnection(SOURCE_URL, "sa", ""));
        when(dataSourceManager.getConnection(targetCfg)).thenAnswer(
                inv -> DriverManager.getConnection(TARGET_URL, "sa", ""));
        when(structureSync.sortByDependency(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stateWriter.loadOrCreateProgress(anyLong(), anyString()))
                .thenReturn(new SyncProgress());

        project = new Project();
        project.setId(11L);
        project.setName("fc-warn");
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

    private SyncContext ctx() {
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.FULL_COMPARE);
        config.getTables().add("BIG");
        config.setSyncStructure(false);
        config.setSyncViews(false);
        config.setSyncProcedures(false);
        config.setSyncData(true);
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

    @Test
    void anOversizedFullCompareTableIsWarnedOnceAndStillSynced() throws SQLException {
        // Any non-empty table is over the cap: the test is about the warning, not about
        // filling a table with 20 001 rows.
        properties.setFullCompareMaxRows(0L);
        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncEngine.class);
        try {
            engine.runCycle(ctx());
            engine.runCycle(ctx());
        } finally {
            LogCapture.detach(SyncEngine.class, logs);
        }

        List<String> warnings = LogCapture.messagesAt(Level.WARN, logs).stream()
                .filter(message -> message.contains("BIG"))
                .filter(message -> message.contains("FULL_COMPARE")
                        || message.contains("full compare"))
                .toList();
        assertThat(warnings)
                .as("the operator must learn about the cost without a warning per poll")
                .hasSize(1);

        // The warning is advisory: the table still syncs on the same cycle.
        try (Connection c = DriverManager.getConnection(TARGET_URL, "sa", "");
             Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM BIG")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void aTableWithinTheCapButHeavyForTheIntervalIsWarned() throws SQLException {
        // 2 000 rows at the 2-second default is ~2 s of work per cycle, over half the
        // interval — heavy, though nowhere near the 20 000-row cap.
        try (Statement st = source.createStatement()) {
            st.execute("MERGE INTO BIG (id, name) SELECT X, 'a' FROM SYSTEM_RANGE(2, 2000)");
        }

        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncEngine.class);
        try {
            engine.runCycle(ctx());
        } finally {
            LogCapture.detach(SyncEngine.class, logs);
        }

        assertThat(LogCapture.messagesAt(Level.WARN, logs))
                .anyMatch(message -> message.contains("BIG")
                        && message.contains("FULL_COMPARE")
                        && message.contains("reads and writes"));
    }

    /**
     * The once-per-process throttle is per project: resetting or deleting a project drops its
     * entries, so the next run warns afresh instead of staying silent on a stale memory.
     */
    @Test
    void evictingTheProjectLetsTheWarningBeIssuedAgain() throws SQLException {
        properties.setFullCompareMaxRows(0L);
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
                        && (message.contains("FULL_COMPARE")
                                || message.contains("full compare")));
    }

    @Test
    void aPollingProjectDoesNotProduceTheWarning() {
        SyncConfig polling = new SyncConfig();
        polling.setCaptureStrategy(CaptureStrategy.POLLING);
        polling.getTables().add("BIG");
        polling.setSyncStructure(false);
        polling.setSyncViews(false);
        polling.setSyncProcedures(false);
        polling.setSyncData(true);
        polling.setDisableTargetConstraints(false);
        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(polling)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceReader(new GenericMetadataReader())
                .targetReader(new GenericMetadataReader())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();

        ListAppender<ILoggingEvent> logs = LogCapture.attach(SyncEngine.class);
        try {
            engine.runCycle(ctx);
        } finally {
            LogCapture.detach(SyncEngine.class, logs);
        }

        assertThat(LogCapture.messagesAt(Level.WARN, logs))
                .noneMatch(message -> message.contains("full compare")
                        || message.contains("FULL_COMPARE"));
    }
}
