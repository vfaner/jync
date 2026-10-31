package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.cdc.LogCapture;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The cursor column is the one column every incremental read touches; without an index on it
 * the window SELECT, the {@code MAX} watermark and the audit are all full scans. The tool
 * knows this from the dictionary, so it says so once per table instead of waiting for the
 * read to grow large enough to time out.
 */
class DataSyncServiceCursorIndexAdviceTest {

    private static final long LARGE = 5_000_000L;
    private static final CursorStrategy UPDATED =
            CursorStrategy.identity("UPDATED", Types.BIGINT, "update time");

    private Connection source;
    private Connection target;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:advice_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:advice_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE EVENTS (ID BIGINT PRIMARY KEY, UPDATED BIGINT, V VARCHAR(10))");
        exec(target, "CREATE TABLE EVENTS (ID BIGINT PRIMARY KEY, UPDATED BIGINT, V VARCHAR(10))");
        exec(source, "INSERT INTO EVENTS VALUES (1, 1, 'a'), (2, 2, 'b')");

        ctx = SyncContext.builder()
                .project(project())
                .config(new SyncConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        source.close();
        target.close();
    }

    @Test
    void advisesAnIndexOnALargeTableWhoseCursorColumnIsNotIndexed() {
        ListAppender<ILoggingEvent> logs = LogCapture.attach(DataSyncService.class);
        try {
            new DataSyncService(new SyncProperties())
                    .syncTable(source, target, table(LARGE), UPDATED, progress(), ctx);

            assertThat(advice(logs))
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains("EVENTS").contains("UPDATED").contains("Add an index"));
        } finally {
            LogCapture.detach(DataSyncService.class, logs);
        }
    }

    @Test
    void staysQuietWhenTheCursorColumnLeadsAnIndex() throws SQLException {
        exec(source, "CREATE INDEX IX_EVENTS_UPDATED ON EVENTS (UPDATED)");
        ListAppender<ILoggingEvent> logs = LogCapture.attach(DataSyncService.class);
        try {
            new DataSyncService(new SyncProperties())
                    .syncTable(source, target, table(LARGE), UPDATED, progress(), ctx);

            assertThat(advice(logs)).isEmpty();
        } finally {
            LogCapture.detach(DataSyncService.class, logs);
        }
    }

    @Test
    void staysQuietWhenTheTableIsSmall() {
        ListAppender<ILoggingEvent> logs = LogCapture.attach(DataSyncService.class);
        try {
            new DataSyncService(new SyncProperties())
                    .syncTable(source, target, table(10L), UPDATED, progress(), ctx);

            assertThat(advice(logs)).isEmpty();
        } finally {
            LogCapture.detach(DataSyncService.class, logs);
        }
    }

    @Test
    void advisesOnlyOncePerTable() {
        ListAppender<ILoggingEvent> logs = LogCapture.attach(DataSyncService.class);
        try {
            DataSyncService service = new DataSyncService(new SyncProperties());
            service.syncTable(source, target, table(LARGE), UPDATED, progress(), ctx);
            service.syncTable(source, target, table(LARGE), UPDATED, progress(), ctx);

            assertThat(advice(logs))
                    .as("a two-second poll must not repeat the advice on every cycle")
                    .hasSize(1);
        } finally {
            LogCapture.detach(DataSyncService.class, logs);
        }
    }

    private static List<String> advice(ListAppender<ILoggingEvent> logs) {
        return LogCapture.messagesAt(Level.WARN, logs).stream()
                .filter(message -> message.startsWith("Table EVENTS"))
                .filter(message -> message.contains("no index"))
                .toList();
    }

    private static SyncProgress progress() {
        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("0");
        return progress;
    }

    private static Project project() {
        Project project = new Project();
        project.setId(7L);
        project.setName("advice");
        return project;
    }

    private static TableMeta table(Long estimate) {
        TableMeta table = new TableMeta();
        table.setName("EVENTS");
        table.setPrimaryKeys(List.of("ID"));
        table.setApproximateRowCount(estimate);
        return table;
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
