package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The emulated-upsert path (update-by-key, insert when the key is absent) retries the UPDATE
 * when the INSERT hits a unique conflict — that is how a concurrent writer's row converges.
 *
 * <p>SQLState 23 is the whole integrity-constraint class, not just unique conflicts: NOT NULL
 * (23502), foreign key (23503) and CHECK (23514) failures all start with 23. Treating those as
 * "row already present" retried the UPDATE, saw zero rows, and dropped the row with no error
 * and no statistic — silent data loss. They must surface as a failure instead.
 */
class DataSyncServiceIntegrityViolationTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection(
                "jdbc:h2:mem:integrity_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection(
                "jdbc:h2:mem:integrity_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50), AMOUNT INT)");
        // The target is stricter than the source: a row the source holds legally cannot be
        // stored here. The write must fail loudly rather than disappear.
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50) NOT NULL,"
                + " AMOUNT INT CHECK (AMOUNT >= 0))");
        exec(source, "INSERT INTO ITEMS VALUES (1, NULL, 10)");

        Project project = new Project();
        project.setId(11L);
        ctx = SyncContext.builder()
                .project(project)
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
    void aNotNullViolationFailsTheTableInsteadOfVanishingAsAUniqueConflict() throws SQLException {
        DataSyncService.TableSyncResult result = service().syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess())
                .as("a NOT NULL violation must not be read as 'row already present'")
                .isFalse();
        assertThat(result.getError()).containsIgnoringCase("null");
        assertThat(count()).isZero();
    }

    @Test
    void aCheckViolationFailsTheTableInsteadOfVanishingAsAUniqueConflict() throws SQLException {
        exec(source, "DELETE FROM ITEMS");
        exec(source, "INSERT INTO ITEMS VALUES (2, 'ok', -5)");

        DataSyncService.TableSyncResult result = service().syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess())
                .as("a CHECK violation must not be read as 'row already present'")
                .isFalse();
        assertThat(count()).isZero();
    }

    private long count() throws SQLException {
        try (Statement st = target.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ITEMS")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private DataSyncService service() {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(0);
        return new DataSyncService(properties, mock(ChunkCheckpointRepository.class));
    }

    private TableMeta itemsMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        ColumnMeta amount = new ColumnMeta();
        amount.setName("AMOUNT");
        amount.setJdbcType(Types.INTEGER);
        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, name, amount));
        return table;
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
