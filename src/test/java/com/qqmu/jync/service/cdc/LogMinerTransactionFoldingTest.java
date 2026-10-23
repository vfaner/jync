package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * LogMiner 的事务缓冲必须与 binlog / PG / SQL Server 三条路径同语义:
 * <ul>
 *   <li>同一事务里同一主键的 DELETE 后 INSERT(源端常见的"先删后插")落成插入后的行——
 *       刷新按 upsert 先、delete 后的顺序执行,不折叠就会写完再删掉,静默丢行;</li>
 *   <li>改变主键的 UPDATE 在目标上删掉旧键——只 upsert 新键会把旧行永远留在目标。</li>
 * </ul>
 */
class LogMinerTransactionFoldingTest {

    private static final String H2_URL =
            "jdbc:h2:mem:lm_folding;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    private Connection source;
    private CdcPositionStore positionStore;
    private DataSourceManager dataSourceManager;
    private SyncProperties properties;
    private MetadataReader reader;

    @BeforeEach
    void setUp() throws SQLException {
        source = mock(Connection.class);
        Statement probeStmt = mock(Statement.class);
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        when(probeStmt.executeQuery(anyString())).thenReturn(emptyRs);
        when(source.createStatement()).thenReturn(probeStmt);

        positionStore = mock(CdcPositionStore.class);
        CdcPosition anchored = new CdcPosition();
        anchored.setProjectId(1L);
        anchored.setScn(1_000L);
        anchored.setBaselineDone(true);
        when(positionStore.find(1L)).thenReturn(Optional.of(anchored));

        dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnection(any())).thenReturn(source);

        reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenAnswer(inv ->
                tableMeta((String) inv.getArgument(2)));

        properties = new SyncProperties();
        properties.getCdc().setReconnectBackoffMs(1L);
        properties.getCdc().setReconnectBackoffMaxMs(1L);
        properties.getCdc().getLogminer().setIdlePollMs(1);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void deleteThenInsertOfTheSameKeyInOneTransactionKeepsTheInsertedRow() throws Exception {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE fold (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        }
        // 同一 CSN = 同一事务。"先删后插"是源端常见写法,最终状态是插入的那一行。
        runConsumer(session(List.of(
                row("delete from \"SRC\".\"FOLD\" where \"ID\" = '1' and \"NAME\" = 'old'"
                        + " and ROWID = 'AAA';", "DELETE", "FOLD"),
                row("insert into \"SRC\".\"FOLD\"(\"ID\",\"NAME\") values ('1','new');",
                        "INSERT", "FOLD"))), 2);

        assertThat(rowsOf("fold")).containsExactly("1|new");
    }

    @Test
    void anUpdateThatMovesThePrimaryKeyDeletesTheOldRow() throws Exception {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE pk_move (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            // 目标上已经有基线带过来的旧键行。
            st.execute("INSERT INTO pk_move (id, name) VALUES (1, 'old')");
        }
        runConsumer(session(List.of(
                row("update \"SRC\".\"PK_MOVE\" set \"ID\" = '2', \"NAME\" = 'new'"
                        + " where \"ID\" = '1' and \"NAME\" = 'old' and ROWID = 'AAA';",
                        "UPDATE", "PK_MOVE"))), 2);

        // 只 upsert 新键会留下 id=1 的幽灵行;正确结果只有新键。
        assertThat(rowsOf("pk_move")).containsExactly("2|new");
    }

    // --- fixtures -------------------------------------------------------------------

    private LogMinerSession.Row row(String redo, String operation, String table) {
        return new LogMinerSession.Row("rs-" + operation + "-" + table, 1_000L, operation,
                "SRC", table, redo, new Timestamp(System.currentTimeMillis()), 0);
    }

    private static LogMinerSession session(List<LogMinerSession.Row> rows) {
        return new LogMinerSession(null, 1_000L, 1_000L, null, null) {
            private boolean consumed;

            @Override
            public List<LogMinerSession.Row> nextBatch() {
                if (consumed) {
                    return Collections.emptyList();
                }
                consumed = true;
                return rows;
            }

            @Override
            public long highWaterScn() {
                return 1_000L;
            }

            @Override
            public void close() {
                // END_LOGMNR would run on a null source connection; nothing to do here.
            }
        };
    }

    /** Both fixture tables have the same shape: ID (PK, BIGINT) and NAME (VARCHAR). */
    private static TableMeta tableMeta(String name) {
        TableMeta meta = new TableMeta();
        meta.setName(name);
        meta.getColumns().add(column("ID", Types.BIGINT, true));
        meta.getColumns().add(column("NAME", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("ID");
        return meta;
    }

    private void runConsumer(LogMinerSession session, int grants) throws Exception {
        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceConfig(new DatabaseConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .sourceReader(reader)
                .connectionProvider(cfg -> h2conn())
                .build();

        Project project = new Project();
        project.setId(1L);
        project.setName("lm-folding");
        LogMinerCdcConsumer consumer = new LogMinerCdcConsumer(project, boundedLock(grants), ctx,
                positionStore, mock(CdcBaselineService.class), mock(SyncTaskStore.class),
                mock(SyncStateWriter.class), dataSourceManager, new CdcRuntimeStats(),
                properties, (c, s, b) -> session);
        consumer.run();
    }

    private static SyncLockService.LockHandle boundedLock(int grants) {
        AtomicInteger remaining = new AtomicInteger(grants);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenAnswer(inv -> remaining.decrementAndGet() >= 0);
        return lock;
    }

    private List<String> rowsOf(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = h2conn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM " + table + " ORDER BY id")) {
            while (rs.next()) {
                out.add(rs.getLong("id") + "|" + rs.getString("name"));
            }
        }
        return out;
    }

    private static ColumnMeta column(String name, int jdbcType, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(pk);
        return column;
    }

    private static Connection h2conn() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }
}
