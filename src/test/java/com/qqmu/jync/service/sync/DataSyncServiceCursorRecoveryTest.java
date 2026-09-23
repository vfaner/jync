package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * Covers the guard against a stored cursor the active strategy cannot parse.
 *
 * <p>A null cursor means "start over" and is safe. A cursor that is present but unreadable —
 * a full-compare fingerprint surviving a switch to identity, or a corrupted value — used to
 * slip into the window predicate as {@code > NULL}: zero rows are read, yet the cycle still
 * advanced the cursor to the watermark, skipping the undelivered range forever. The recovery
 * is to treat it like no cursor at all and re-establish one via a full load.
 */
class DataSyncServiceCursorRecoveryTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;
    private TableMeta table;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:cursor_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:cursor_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");

        service = new DataSyncService(new SyncProperties());
        table = new TableMeta();
        table.setName("ITEMS");
        ctx = SyncContext.builder()
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
    void anUnreadableCursorTriggersAFullReloadInsteadOfSkippingTheTable() throws SQLException {
        insert(source, 1, 2, 3);

        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        // The fingerprint a full-compare strategy stores, surviving a switch to identity.
        progress.setLastSyncValue("fc:2");

        DataSyncService.TableSyncResult result =
                service.syncTable(source, target, table, IDENTITY, progress, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isInitialLoad()).isTrue();
        // The undelivered rows arrive instead of being skipped...
        assertThat(count()).isEqualTo(3);
        // ...and the new cursor is one the identity strategy can actually read.
        assertThat(result.getNewCursorValue()).isEqualTo("3");
    }

    @Test
    void anyGarbageCursorRecoversTheSameWay() throws SQLException {
        insert(source, 1, 2);

        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("not-a-number");

        DataSyncService.TableSyncResult result =
                service.syncTable(source, target, table, IDENTITY, progress, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isInitialLoad()).isTrue();
        assertThat(count()).isEqualTo(2);
        assertThat(result.getNewCursorValue()).isEqualTo("2");
    }

    @Test
    void aReadableCursorStillSyncsOnlyTheNewWindow() throws SQLException {
        // The recovery must not over-trigger: a parseable cursor keeps incremental semantics,
        // otherwise every cycle would degrade into a full reload.
        insert(source, 1, 2, 3);
        insert(target, 1);

        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("1");

        DataSyncService.TableSyncResult result =
                service.syncTable(source, target, table, IDENTITY, progress, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isInitialLoad()).isFalse();
        assertThat(result.getRowsWritten()).isEqualTo(2);
        assertThat(count()).isEqualTo(3);
        assertThat(result.getNewCursorValue()).isEqualTo("3");
    }

    private long count() throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ITEMS")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void insert(Connection conn, long... ids) throws SQLException {
        for (long id : ids) {
            exec(conn, "INSERT INTO ITEMS VALUES (" + id + ", 'row" + id + "')");
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
