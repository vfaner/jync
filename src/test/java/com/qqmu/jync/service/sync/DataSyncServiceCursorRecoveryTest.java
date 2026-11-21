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

    /**
     * The cursor column is entirely NULL: the full load seeds the target but stores no
     * cursor. Without the marker this state looks like "never loaded" forever — a keyed
     * table re-upserts itself on every poll, and a keyless one is refused every cycle.
     */
    @Test
    void aTableWhoseCursorColumnIsAllNullIdlesInsteadOfReloadingEveryPoll() throws SQLException {
        exec(source, "ALTER TABLE ITEMS ADD UPDATED TIMESTAMP");
        exec(target, "ALTER TABLE ITEMS ADD UPDATED TIMESTAMP");
        exec(source, "INSERT INTO ITEMS VALUES (1, 'a', NULL), (2, 'b', NULL), (3, 'c', NULL)");
        exec(target, "INSERT INTO ITEMS VALUES (1, 'a', NULL), (2, 'b', NULL), (3, 'c', NULL)");
        CursorStrategy byUpdated = CursorStrategy.timestamp("UPDATED", Types.TIMESTAMP,
                "nullable updated timestamp");

        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(null);
        progress.setFullLoadWithoutCursor(true);

        DataSyncService.TableSyncResult result =
                service.syncTable(source, target, keyedItems(), byUpdated, progress, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isInitialLoad()).isFalse();
        assertThat(result.isSkipped()).isTrue();
        assertThat(result.getRowsWritten()).isZero();
        assertThat(result.getSkipReason()).contains("UPDATED");
        // Deletes are key-matched: they must keep propagating while the reload idles.
        assertThat(result.isDeletionSyncStillValid()).isTrue();
    }

    /** A keyless table in the same state used to fail every cycle ("target holds rows"). */
    @Test
    void aKeylessTableWithAnAllNullCursorIsSkippedInsteadOfFailingEveryCycle()
            throws SQLException {
        exec(source, "CREATE TABLE KEYLESS (ID BIGINT, NAME VARCHAR(50), UPDATED TIMESTAMP)");
        exec(target, "CREATE TABLE KEYLESS (ID BIGINT, NAME VARCHAR(50), UPDATED TIMESTAMP)");
        exec(source, "INSERT INTO KEYLESS VALUES (1, 'a', NULL)");
        exec(target, "INSERT INTO KEYLESS VALUES (1, 'a', NULL)");
        TableMeta keyless = new TableMeta();
        keyless.setName("KEYLESS");
        keyless.getColumns().add(column("ID", Types.BIGINT));
        keyless.getColumns().add(column("NAME", Types.VARCHAR));
        keyless.getColumns().add(column("UPDATED", Types.TIMESTAMP));
        CursorStrategy byUpdated = CursorStrategy.timestamp("UPDATED", Types.TIMESTAMP,
                "nullable updated timestamp");

        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setFullLoadWithoutCursor(true);

        DataSyncService.TableSyncResult result =
                service.syncTable(source, target, keyless, byUpdated, progress, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isSkipped()).isTrue();
        assertThat(result.getError()).isNull();
        assertThat(result.isDeletionSyncStillValid()).isTrue();
    }

    /**
     * A keyless table cannot be reloaded while the marker is set (the target holds the seeded
     * rows and a re-insert would duplicate them). It must idle with the remedy in the reason,
     * not fall back into the permanent "target already holds rows" error.
     */
    @Test
    void aKeylessTableWithCursorValuesStillIdlesInsteadOfFailing() throws SQLException {
        exec(source, "CREATE TABLE KEYLESS2 (ID BIGINT, UPDATED TIMESTAMP)");
        exec(target, "CREATE TABLE KEYLESS2 (ID BIGINT, UPDATED TIMESTAMP)");
        exec(source, "INSERT INTO KEYLESS2 VALUES (1, TIMESTAMP '2026-01-01 00:00:00')");
        exec(target, "INSERT INTO KEYLESS2 VALUES (1, NULL)");
        TableMeta keyless = new TableMeta();
        keyless.setName("KEYLESS2");
        keyless.getColumns().add(column("ID", Types.BIGINT));
        keyless.getColumns().add(column("UPDATED", Types.TIMESTAMP));
        CursorStrategy byUpdated = CursorStrategy.timestamp("UPDATED", Types.TIMESTAMP, "t");

        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setFullLoadWithoutCursor(true);

        DataSyncService.TableSyncResult result =
                service.syncTable(source, target, keyless, byUpdated, progress, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isSkipped()).isTrue();
        assertThat(result.getSkipReason()).contains("reset the cursor");
        assertThat(result.getError()).isNull();
    }

    /** Once the column gains a value the stored null cursor reloads and re-establishes it. */
    @Test
    void aTableThatGainsCursorValuesReloadsOnceAndResumes() throws SQLException {
        exec(source, "ALTER TABLE ITEMS ADD UPDATED TIMESTAMP");
        exec(target, "ALTER TABLE ITEMS ADD UPDATED TIMESTAMP");
        exec(source, "INSERT INTO ITEMS VALUES (1, 'row1', TIMESTAMP '2026-01-01 00:00:00')");
        exec(target, "INSERT INTO ITEMS VALUES (1, 'row1', NULL)");
        CursorStrategy byUpdated = CursorStrategy.timestamp("UPDATED", Types.TIMESTAMP,
                "nullable updated timestamp");

        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue(null);
        progress.setFullLoadWithoutCursor(true);

        DataSyncService.TableSyncResult result =
                service.syncTable(source, target, keyedItems(), byUpdated, progress, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isInitialLoad()).isTrue();
        assertThat(result.getNewCursorValue()).isNotNull();
        assertThat(count()).isEqualTo(1);
    }

    private static TableMeta keyedItems() {
        TableMeta meta = new TableMeta();
        meta.setName("ITEMS");
        meta.getColumns().add(column("ID", Types.BIGINT));
        meta.getColumns().add(column("NAME", Types.VARCHAR));
        meta.getColumns().add(column("UPDATED", Types.TIMESTAMP));
        meta.getPrimaryKeys().add("ID");
        return meta;
    }

    private static com.qqmu.jync.dto.meta.ColumnMeta column(String name, int jdbcType) {
        com.qqmu.jync.dto.meta.ColumnMeta column = new com.qqmu.jync.dto.meta.ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
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
