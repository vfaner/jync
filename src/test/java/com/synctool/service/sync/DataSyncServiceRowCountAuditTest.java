package com.synctool.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.synctool.config.SyncProperties;
import com.synctool.dto.SyncConfig;
import com.synctool.dto.meta.TableMeta;
import com.synctool.model.SyncProgress;
import com.synctool.service.converter.GenericSqlDialect;
import com.synctool.service.monitor.CursorStrategy;

/**
 * Exercises the row-count audit against real tables, because the whole point of the audit is
 * that it asks the database rather than trusting bookkeeping — a mock would only assert that
 * the code calls the methods the test already assumes it calls.
 */
class DataSyncServiceRowCountAuditTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;
    private TableMeta table;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:audit_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:audit_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");

        service = new DataSyncService(new SyncProperties());
        table = new TableMeta();
        table.setName("ITEMS");
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
    void reportsTargetShortWhenSyncedRowsAreMissing() throws SQLException {
        // The reseed trap: the cursor sat at 100, the source was then truncated and refilled
        // with ids starting from 1, so every replacement row is below the cursor and invisible
        // to an identity window that only looks higher.
        insert(source, 1, 2, 3);
        insert(target, 100);

        DataSyncService.RowCountAudit audit = audit("100");

        assertThat(audit).isNotNull();
        assertThat(audit.getSourceRows()).isEqualTo(3);
        assertThat(audit.getTargetRows()).isEqualTo(1);
        assertThat(audit.isTargetShort()).isTrue();
    }

    @Test
    void acceptsTargetThatMatches() throws SQLException {
        insert(source, 1, 2, 3);
        insert(target, 1, 2, 3);

        assertThat(audit("3").isTargetShort()).isFalse();
    }

    @Test
    void acceptsTargetHoldingExtraRows() throws SQLException {
        // Rows deleted at the source survive in the target when syncDeletes is off. The
        // invariant the user asked for is source <= target, so a surplus is not a fault.
        insert(source, 1, 3);
        insert(target, 1, 2, 3);

        assertThat(audit("3").isTargetShort()).isFalse();
    }

    @Test
    void ignoresSourceRowsAboveTheCursor() throws SQLException {
        // 4 and 5 arrived after the last window closed. They are legitimately absent from the
        // target, and counting them would make the audit fire on every healthy busy table.
        insert(source, 1, 2, 3, 4, 5);
        insert(target, 1, 2, 3);

        DataSyncService.RowCountAudit audit = audit("3");

        assertThat(audit.getSourceRows()).isEqualTo(3);
        assertThat(audit.isTargetShort()).isFalse();
    }

    @Test
    void ignoresTargetRowsAboveTheCursor() throws SQLException {
        // A target seeded by other means could hold high ids that say nothing about whether the
        // synced range is complete. Bounding both sides stops those from masking a real gap.
        insert(source, 1, 2, 3);
        insert(target, 1, 9, 10);

        DataSyncService.RowCountAudit audit = audit("3");

        assertThat(audit.getTargetRows()).isEqualTo(1);
        assertThat(audit.isTargetShort()).isTrue();
    }

    @Test
    void declinesToAuditBeforeACursorExists() throws SQLException {
        insert(source, 1, 2, 3);

        assertThat(audit(null)).isNull();
    }

    @Test
    void declinesToAuditNonIncrementalTables() throws SQLException {
        insert(source, 1, 2, 3);

        SyncProgress progress = new SyncProgress();
        progress.setLastSyncValue("fc:3");
        assertThat(service.auditSyncedRowCounts(source, target, table,
                CursorStrategy.fullCompare("no cursor column"), progress, ctx)).isNull();
        assertThat(service.auditSyncedRowCounts(source, target, table,
                CursorStrategy.none("no safe strategy"), progress, ctx)).isNull();
    }

    private DataSyncService.RowCountAudit audit(String cursor) throws SQLException {
        SyncProgress progress = new SyncProgress();
        progress.setLastSyncValue(cursor);
        return service.auditSyncedRowCounts(source, target, table, IDENTITY, progress, ctx);
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
