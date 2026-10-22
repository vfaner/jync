package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.monitor.CursorStrategy;

/**
 * A PostgreSQL-family source is read inside an explicit transaction so the driver streams
 * instead of buffering the whole result set. The session must cover the copy and only the
 * copy: on success the read transaction is committed before the target commits, on failure
 * it is rolled back, and the connection's original autocommit comes back either way.
 */
class DataSyncServiceSourceStreamingTest {

    private static final CursorStrategy IDENTITY =
            CursorStrategy.identity("ID", Types.BIGINT, "numeric primary key");

    private Connection reallySource;
    private Connection source;
    private RecordingConnection recorder;
    private Connection target;
    private SyncProperties properties;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        reallySource = DriverManager.getConnection("jdbc:h2:mem:ss_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:ss_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        exec(reallySource, "CREATE TABLE DOCS (ID BIGINT PRIMARY KEY, BODY CLOB)");
        exec(target, "CREATE TABLE DOCS (ID BIGINT PRIMARY KEY, BODY CLOB)");

        recorder = new RecordingConnection(reallySource);
        source = recorder.proxy();

        properties = new SyncProperties();
        properties.setChunkSize(0);
        properties.setBatchSize(50);

        Project project = new Project();
        project.setId(11L);
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceType(DatabaseType.POSTGRESQL)
                .targetType(DatabaseType.CUSTOM)
                .sourceDialect(new PostgresDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .fullLoadBatchSize(50)
                .fullLoadFetchSize(100)
                .fullLoadParallelism(1)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(reallySource, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        reallySource.close();
        target.close();
    }

    @Test
    void theSourceCopyRunsInItsOwnTransactionAndRestoresAutocommit() throws SQLException {
        insert(1, "body");

        DataSyncService.TableSyncResult result = sync();

        assertThat(result.isSuccess()).as(result.getError()).isTrue();
        assertThat(recorder.transactionCalls()).containsExactly(
                "setAutoCommit(false)", "commit()", "setAutoCommit(true)");
        assertThat(reallySource.getAutoCommit()).isTrue();
        assertThat(targetBody(1)).isEqualTo("body");
    }

    @Test
    void sourceStreamingCanBeTurnedOffForTheOldBehaviour() throws SQLException {
        properties.getLob().setEnableSourceStreaming(false);
        insert(1, "body");

        assertThat(sync().isSuccess()).isTrue();

        assertThat(recorder.transactionCalls()).isEmpty();
        assertThat(reallySource.getAutoCommit()).isTrue();
    }

    @Test
    void theMasterStreamingSwitchAlsoLeavesTheSourceAlone() throws SQLException {
        properties.getLob().setStreamingEnabled(false);
        insert(1, "body");

        assertThat(sync().isSuccess()).isTrue();

        assertThat(recorder.transactionCalls()).isEmpty();
    }

    @Test
    void aFailedCopyRollsTheSourceReadBackAndStillRestoresAutocommit() throws SQLException {
        exec(target, "DROP TABLE DOCS");
        exec(target, "CREATE TABLE DOCS (ID BIGINT PRIMARY KEY CHECK (ID > 1), BODY CLOB)");
        insert(1, "body");

        assertThat(sync().isSuccess()).isFalse();

        assertThat(recorder.transactionCalls()).containsExactly(
                "setAutoCommit(false)", "rollback()", "setAutoCommit(true)");
        assertThat(reallySource.getAutoCommit()).isTrue();
    }

    // --- fixtures -------------------------------------------------------------------

    private DataSyncService.TableSyncResult sync() throws SQLException {
        DataSyncService service = new DataSyncService(properties,
                mock(ChunkCheckpointRepository.class));
        return service.syncTable(source, target, docsMeta(), IDENTITY, new SyncProgress(), ctx);
    }

    private void insert(long id, String body) throws SQLException {
        try (PreparedStatement ps = reallySource.prepareStatement(
                "INSERT INTO DOCS (ID, BODY) VALUES (?, ?)")) {
            ps.setLong(1, id);
            ps.setCharacterStream(2, new StringReader(body), body.length());
            ps.executeUpdate();
        }
    }

    private String targetBody(long id) throws SQLException {
        try (PreparedStatement ps = target.prepareStatement(
                "SELECT BODY FROM DOCS WHERE ID = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static TableMeta docsMeta() {
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        ColumnMeta body = new ColumnMeta();
        body.setName("BODY");
        body.setJdbcType(Types.CLOB);
        TableMeta table = new TableMeta();
        table.setName("DOCS");
        table.setPrimaryKeys(List.of("ID"));
        table.setColumns(List.of(id, body));
        table.setApproximateRowCount(1L);
        return table;
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * Records the connection calls that start or end a transaction, then delegates. Only
     * those three methods are recorded: everything else is ordinary driver traffic.
     */
    private static final class RecordingConnection implements InvocationHandler {

        private final Connection delegate;
        private final List<String> transactionCalls = new ArrayList<>();

        RecordingConnection(Connection delegate) {
            this.delegate = delegate;
        }

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, this);
        }

        List<String> transactionCalls() {
            return transactionCalls;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("setAutoCommit".equals(name)) {
                transactionCalls.add("setAutoCommit(" + args[0] + ")");
            } else if ("commit".equals(name) || "rollback".equals(name)) {
                transactionCalls.add(name + "()");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
