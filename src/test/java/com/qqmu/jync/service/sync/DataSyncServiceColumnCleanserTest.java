package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * The polling/full-load write engine must apply the project's column rules before the row
 * reaches the target — on both the classic streamed load and the chunked initial load.
 */
class DataSyncServiceColumnCleanserTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection source;
    private Connection target;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:cleanse_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:cleanse_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(source, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(target, "CREATE TABLE ITEMS (ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
        exec(source, "INSERT INTO ITEMS VALUES (1, '  Alice  '), (2, 'BOB')");

        ColumnRule trim = new ColumnRule();
        trim.setTable("ITEMS");
        trim.setColumn("NAME");
        trim.setType(ColumnRuleType.TRIM);
        ColumnRule lower = new ColumnRule();
        lower.setTable("ITEMS");
        lower.setColumn("NAME");
        lower.setType(ColumnRuleType.LOWER);
        SyncConfig config = new SyncConfig();
        config.setColumnRules(List.of(trim, lower));

        Project project = new Project();
        project.setId(8L);
        ctx = SyncContext.builder()
                .project(project)
                .config(config)
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
    void theClassicStreamedLoadWritesTheCleansedValue() throws SQLException {
        DataSyncService service = service(0);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(names()).containsExactly("alice", "bob");
    }

    @Test
    void theChunkedInitialLoadWritesTheCleansedValue() throws SQLException {
        DataSyncService service = service(1);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, itemsMeta(), IDENTITY, new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(names()).containsExactly("alice", "bob");
    }

    /**
     * A rule on the primary key must not move the keyset boundary. The boundary is bound
     * against the raw source keys on the next page, so taking it from the cleansed row skips
     * every source key between the cleansed and the raw value (here 'B' > 'a' but 'B' < ...).
     */
    @Test
    void aRuleOnAStringPrimaryKeyDoesNotSkipRowsInTheChunkedLoad() throws SQLException {
        exec(source, "CREATE TABLE CODES (CODE VARCHAR(32) PRIMARY KEY)");
        exec(target, "CREATE TABLE CODES (CODE VARCHAR(32) PRIMARY KEY)");
        exec(source, "INSERT INTO CODES VALUES ('A'), ('B')");
        ColumnRule lower = new ColumnRule();
        lower.setTable("CODES");
        lower.setColumn("CODE");
        lower.setType(ColumnRuleType.LOWER);
        ctx.getConfig().setColumnRules(List.of(lower));
        DataSyncService service = service(1);

        DataSyncService.TableSyncResult result = service.syncTable(
                source, target, codesMeta(), CursorStrategy.fullCompare("probe"),
                new SyncProgress(), ctx);

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(codes()).containsExactly("a", "b");
    }

    @Test
    void deletionDetectionComparesSourceKeysThroughTheSameRules() throws SQLException {
        exec(source, "CREATE TABLE CODES (CODE VARCHAR(32) PRIMARY KEY)");
        exec(target, "CREATE TABLE CODES (CODE VARCHAR(32) PRIMARY KEY)");
        exec(source, "INSERT INTO CODES VALUES ('tmp_A')");
        // The upsert already stored the cleansed key, so the raw source key must be
        // transformed before the comparison — otherwise every row looks deleted.
        exec(target, "INSERT INTO CODES VALUES ('A')");
        ColumnRule strip = new ColumnRule();
        strip.setTable("CODES");
        strip.setColumn("CODE");
        strip.setType(ColumnRuleType.STRIP_PREFIX);
        strip.setArg("tmp_");
        ctx.getConfig().setColumnRules(List.of(strip));
        DataSyncService service = service(0);

        int deleted = service.syncDeletions(source, target, codesMeta(), ctx);

        assertThat(deleted).isZero();
        assertThat(count("CODES")).isEqualTo(1);
    }

    private TableMeta codesMeta() {
        ColumnMeta code = new ColumnMeta();
        code.setName("CODE");
        code.setJdbcType(Types.VARCHAR);
        code.setPrimaryKey(true);
        TableMeta table = new TableMeta();
        table.setName("CODES");
        table.setPrimaryKeys(List.of("CODE"));
        table.setColumns(List.of(code));
        // Without a row estimate detection bows out (MAX_VALUE > the comparison ceiling).
        table.setApproximateRowCount(1L);
        return table;
    }

    private List<String> codes() throws SQLException {
        List<String> codes = new java.util.ArrayList<>();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT CODE FROM CODES ORDER BY CODE")) {
            while (rs.next()) {
                codes.add(rs.getString(1));
            }
        }
        return codes;
    }

    private long count(String tableName) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private List<String> names() throws SQLException {
        List<String> names = new java.util.ArrayList<>();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT NAME FROM ITEMS ORDER BY ID")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private DataSyncService service(int chunkSize) {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(chunkSize);
        return new DataSyncService(properties, mock(ChunkCheckpointRepository.class));
    }

    private TableMeta itemsMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);

        TableMeta table = new TableMeta();
        table.setName("ITEMS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, name));
        return table;
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
