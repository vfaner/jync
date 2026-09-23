package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

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
 * Two "must not fail quietly" guarantees around the initial load and replays:
 *
 * <ul>
 *   <li>A key-only table (every column part of the primary key) has no UPDATE half in the
 *       emulated upsert, so a re-delivered row can only collide. Since a row holding that key
 *       is by definition identical to the rejected one, the collision is a no-op — throwing
 *       on it would wedge the table permanently.</li>
 *   <li>{@code truncateTarget} promising an empty table must keep that promise: when both
 *       TRUNCATE and the DELETE fallback fail, the caller has to hear about it instead of
 *       loading on top of rows the user asked to clear.</li>
 * </ul>
 */
class DataSyncServiceReplaySafetyTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:replay_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:replay_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        service = new DataSyncService(new SyncProperties());
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
    void redeliveredRowsInAKeyOnlyTableAreANoopNotAnError() throws SQLException {
        exec(source, "CREATE TABLE KEYONLY (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE KEYONLY (ID BIGINT PRIMARY KEY)");
        exec(source, "INSERT INTO KEYONLY VALUES (1), (2)");

        TableMeta table = new TableMeta();
        table.setName("KEYONLY");
        table.setPrimaryKeys(List.of("ID")); // without it the table looks keyless

        DataSyncService.TableSyncResult first = service.syncTable(source, target, table, IDENTITY,
                new SyncProgress(), ctx);
        assertThat(first.isSuccess()).isTrue();
        assertThat(count("KEYONLY")).isEqualTo(2);

        // Replay the same keys: a cursor below the delivered rows re-reads them, and the
        // key-only table can only answer with a unique conflict.
        SyncProgress progress = new SyncProgress();
        progress.setInitialLoadDone(true);
        progress.setLastSyncValue("0");

        DataSyncService.TableSyncResult replay =
                service.syncTable(source, target, table, IDENTITY, progress, ctx);

        assertThat(replay.isSuccess())
                .as("replayed key-only rows must converge, error was: %s", replay.getError())
                .isTrue();
        assertThat(replay.getNewCursorValue()).isEqualTo("2");
        assertThat(count("KEYONLY")).isEqualTo(2);
    }

    @Test
    void truncateEmptiesTheTargetOnTheHappyPath() throws SQLException {
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "INSERT INTO ITEMS VALUES (1, 'old')");

        TableMeta table = new TableMeta();
        table.setName("ITEMS");

        service.truncateTarget(target, table, ctx);

        assertThat(count("ITEMS")).isZero();
    }

    @Test
    void aTruncateWhoseFallbackAlsoFailsThrowsInsteadOfPassingSilently() throws SQLException {
        TableMeta table = new TableMeta();
        table.setName("ITEMS");

        // On a dead connection both TRUNCATE and the DELETE fallback fail; the caller must
        // not proceed to load into a table that was never emptied.
        Connection dead = DriverManager.getConnection(
                "jdbc:h2:mem:replay_dead;DB_CLOSE_DELAY=-1", "sa", "");
        dead.close();

        assertThatThrownBy(() -> service.truncateTarget(dead, table, ctx))
                .isInstanceOf(SQLException.class);
    }

    private long count(String tableName) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
