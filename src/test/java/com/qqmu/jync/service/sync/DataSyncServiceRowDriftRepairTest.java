package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
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
 * The targeted half of a row-count repair, against real tables.
 *
 * <p>When the audit finds the target one row short, clearing the cursor and reloading the
 * whole table is correct but brutal: a 50-million-row table pays a full read and a full
 * rewrite to recover one backfilled row. The targeted repair walks the audited key range,
 * diffs keys (never row bodies), and upserts only the rows that are actually missing — the
 * cursor stays exactly where it was, so nothing is reloaded.
 */
class DataSyncServiceRowDriftRepairTest {

    private static final CursorStrategy BY_UPDATED = CursorStrategy.timestamp(
            "UPDATED", Types.TIMESTAMP, "system update time");

    private static final Timestamp CURSOR = Timestamp.valueOf("2026-01-01 00:00:10");

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;
    private TableMeta table;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:repair_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:repair_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, UPDATED TIMESTAMP,"
                + " NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, UPDATED TIMESTAMP,"
                + " NAME VARCHAR(50))");

        service = new DataSyncService(new SyncProperties());
        table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(java.util.List.of("ID"));
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
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
    void copiesOnlyTheMissingRowAndKeepsTheCursor() throws SQLException {
        sourceRows(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        targetRows("target", 1, 2, 3, 5, 6, 7, 8, 9, 10);
        SyncProgress progress = progress();

        DataSyncService.RowCountAudit audit = audit(progress);
        assertThat(audit.isTargetShort()).isTrue();

        DataSyncService.RowDriftRepair repair =
                service.repairMissingRows(source, target, table, BY_UPDATED, progress, audit, ctx);

        assertThat(repair.isHandled()).isTrue();
        assertThat(repair.getRepairedRows()).isEqualTo(1);
        // The one missing row arrived with the source's own values ...
        assertThat(name(target, 4)).isEqualTo("src-4");
        // ... and nothing else was rewritten: every neighbour still carries the target's copy.
        assertThat(name(target, 5)).isEqualTo("target-5");
        assertThat(name(target, 1)).isEqualTo("target-1");
        assertThat(count(target)).isEqualTo(10);
        // A targeted repair is not a reload: the cursor the audit trusted is still there, so
        // the next cycle continues incrementally instead of re-reading the table.
        assertThat(progress.getLastSyncValue()).isEqualTo(
                JdbcRowMapper.cursorToString(CURSOR));
    }

    @Test
    void neverCopiesRowsAboveTheAuditedCursor() throws SQLException {
        // ID 11 landed after the last window closed. It is legitimately absent from the
        // target; copying it here would be the repair answering a different question.
        sourceRows(1, 2, 3, 4, 5);
        exec(source, "INSERT INTO ITEMS VALUES (11, TIMESTAMP '2026-01-02 00:00:00', 'src-11')");
        targetRows("target", 1, 2, 3, 5);
        SyncProgress progress = progress();

        DataSyncService.RowDriftRepair repair = service.repairMissingRows(source, target, table,
                BY_UPDATED, progress, audit(progress), ctx);

        assertThat(repair.isHandled()).isTrue();
        assertThat(repair.getRepairedRows()).isEqualTo(1);
        assertThat(name(target, 4)).isEqualTo("src-4");
        // Four rows were already there; the repair added the one missing row and nothing else.
        assertThat(count(target)).isEqualTo(5);
        assertThat(name(target, 11)).isNull();
    }

    @Test
    void aCompositeKeyRepairFindsTheMissingTuple() throws SQLException {
        exec(source, "CREATE TABLE LEDGER (TENANT VARCHAR(20), ID BIGINT, UPDATED TIMESTAMP,"
                + " AMOUNT BIGINT, PRIMARY KEY (TENANT, ID))");
        exec(target, "CREATE TABLE LEDGER (TENANT VARCHAR(20), ID BIGINT, UPDATED TIMESTAMP,"
                + " AMOUNT BIGINT, PRIMARY KEY (TENANT, ID))");
        for (Connection conn : new Connection[]{source, target}) {
            exec(conn, "INSERT INTO LEDGER VALUES ('acme', 1, TIMESTAMP '2026-01-01 00:00:01', 10)");
            exec(conn, "INSERT INTO LEDGER VALUES ('globex', 1, TIMESTAMP '2026-01-01 00:00:03', 30)");
        }
        exec(source, "INSERT INTO LEDGER VALUES ('acme', 2, TIMESTAMP '2026-01-01 00:00:02', 20)");
        TableMeta ledger = new TableMeta();
        ledger.setName("LEDGER");
        ledger.setPrimaryKeys(java.util.List.of("TENANT", "ID"));
        SyncProgress progress = progress();

        DataSyncService.RowDriftRepair repair = service.repairMissingRows(source, target, ledger,
                BY_UPDATED, progress, audit(ledger, progress), ctx);

        assertThat(repair.isHandled()).isTrue();
        assertThat(repair.getRepairedRows()).isEqualTo(1);
        try (Statement st = target.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT AMOUNT FROM LEDGER WHERE TENANT = 'acme' AND ID = 2")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(20L);
        }
    }

    @Test
    void aKeylessTableLeavesTheRepairToTheFullReload() throws SQLException {
        // Without a key there is no way to name the missing rows, and an offset walk says
        // nothing about which rows the target already holds.
        sourceRows(1, 2, 3);
        targetRows("target", 1, 2);
        TableMeta keyless = new TableMeta();
        keyless.setName("ITEMS");
        SyncProgress progress = progress();

        DataSyncService.RowDriftRepair repair = service.repairMissingRows(source, target, keyless,
                BY_UPDATED, progress, audit(keyless, progress), ctx);

        assertThat(repair.isHandled()).isFalse();
        assertThat(repair.getReason()).contains("primary key");
        assertThat(count(target)).isEqualTo(2);
    }

    @Test
    void aDriftAboveTheConfiguredCeilingLeavesTheRepairToTheFullReload() throws SQLException {
        // A gap this wide is a table that needs rebuilding, not a handful of rows to patch;
        // continuing to diff would cost more than the reload it replaces.
        service = new DataSyncService(propertiesWithCeiling(2));
        sourceRows(1, 2, 3, 4, 5);
        targetRows("target", 1, 5);
        SyncProgress progress = progress();

        DataSyncService.RowDriftRepair repair = service.repairMissingRows(source, target, table,
                BY_UPDATED, progress, audit(progress), ctx);

        assertThat(repair.isHandled()).isFalse();
        assertThat(repair.getReason()).contains("3 row(s) missing");
        assertThat(count(target)).isEqualTo(2);
    }

    @Test
    void aTargetTheAuditAlreadyAcceptsIsLeftAlone() throws SQLException {
        sourceRows(1, 2, 3);
        targetRows("target", 1, 2, 3);
        SyncProgress progress = progress();

        DataSyncService.RowDriftRepair repair = service.repairMissingRows(source, target, table,
                BY_UPDATED, progress, audit(progress), ctx);

        assertThat(repair.isHandled()).isFalse();
        assertThat(repair.getRepairedRows()).isZero();
    }

    @Test
    void aKeyNormalizesTheSameInstantWhateverTemporalTypeTheDriverReturns() {
        // The source and the target are read through two different drivers. The same
        // TIMESTAMP primary key can come back as Timestamp, LocalDateTime, Instant,
        // OffsetDateTime or ZonedDateTime; if those normalize differently, every shared
        // key looks missing and the repair upserts rows that were never absent.
        Timestamp stamp = Timestamp.valueOf("2026-01-01 00:00:10.123456789");
        String expected = DataSyncService.normalizedKey(java.util.List.of(stamp));

        assertThat(DataSyncService.normalizedKey(java.util.List.of(stamp.toLocalDateTime())))
                .isEqualTo(expected);
        assertThat(DataSyncService.normalizedKey(java.util.List.of(stamp.toInstant())))
                .isEqualTo(expected);
        assertThat(DataSyncService.normalizedKey(java.util.List.of(
                java.time.OffsetDateTime.ofInstant(stamp.toInstant(),
                        java.time.ZoneOffset.UTC))))
                .isEqualTo(expected);
        assertThat(DataSyncService.normalizedKey(java.util.List.of(
                java.time.ZonedDateTime.ofInstant(stamp.toInstant(),
                        java.time.ZoneOffset.UTC))))
                .isEqualTo(expected);
    }

    @Test
    void aDateKeyNormalizesAcrossSqlAndJavaTimeTypes() {
        // DATE columns are legitimately returned as java.sql.Date by one driver and as
        // LocalDate by another.
        java.sql.Date date = java.sql.Date.valueOf("2026-01-01");

        assertThat(DataSyncService.normalizedKey(
                java.util.List.of(java.time.LocalDate.of(2026, 1, 1))))
                .isEqualTo(DataSyncService.normalizedKey(java.util.List.of(date)));
    }

    @Test
    void aTimeKeyNormalizesAcrossSqlAndJavaTimeTypes() {
        // The TIME counterpart of the DATE case above: one driver returns java.sql.Time, the
        // other java.time.LocalTime. LocalTime had no branch, so it fell through to its
        // toString while java.sql.Time normalized as an instant — every shared key then looked
        // missing and the repair re-copied rows that were never absent.
        java.sql.Time time = java.sql.Time.valueOf("10:00:00");

        assertThat(DataSyncService.normalizedKey(
                java.util.List.of(java.time.LocalTime.of(10, 0))))
                .isEqualTo(DataSyncService.normalizedKey(java.util.List.of(time)));
    }

    // --- helpers ---------------------------------------------------------------------

    private SyncProperties propertiesWithCeiling(int maxRows) {
        SyncProperties properties = new SyncProperties();
        properties.setRowCountRepairMaxRows(maxRows);
        return properties;
    }

    private SyncProgress progress() {
        SyncProgress progress = new SyncProgress();
        progress.setLastSyncValue(JdbcRowMapper.cursorToString(CURSOR));
        return progress;
    }

    private DataSyncService.RowCountAudit audit(SyncProgress progress) throws SQLException {
        return audit(table, progress);
    }

    private DataSyncService.RowCountAudit audit(TableMeta meta, SyncProgress progress)
            throws SQLException {
        return service.auditSyncedRowCounts(source, target, meta, BY_UPDATED, progress, ctx);
    }

    /** Source rows 1..n, each with a distinct update time at or below the cursor. */
    private void sourceRows(long... ids) throws SQLException {
        for (long id : ids) {
            exec(source, "INSERT INTO ITEMS VALUES (" + id + ", TIMESTAMP '2026-01-01 00:00:0"
                    + (id % 10) + "', 'src-" + id + "')");
        }
    }

    private void targetRows(String prefix, long... ids) throws SQLException {
        for (long id : ids) {
            exec(target, "INSERT INTO ITEMS VALUES (" + id + ", TIMESTAMP '2026-01-01 00:00:0"
                    + (id % 10) + "', '" + prefix + "-" + id + "')");
        }
    }

    private String name(Connection conn, long id) throws SQLException {
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT NAME FROM ITEMS WHERE ID = " + id)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private long count(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ITEMS")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
