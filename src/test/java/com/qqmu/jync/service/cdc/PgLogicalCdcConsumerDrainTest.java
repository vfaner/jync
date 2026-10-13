package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * F-11(docker 实证 postgres:16):pg_logical_slot_get_changes 的消费<b>不挂在外层
 * 事务上</b> —— confirmed_flush_lsn 在函数执行时立即推进,ROLLBACK 不回卷。旧实现
 * 依赖「失败 rollback → 下周期重读」:毒事务(如目标缺列 42703)只失败一次就从 slot
 * 永久消失,之后数百周期读到空 slot,行永远丢失。实证序列:
 *
 * <pre>
 * BEGIN; SELECT ... pg_logical_slot_get_changes(...); ROLLBACK;
 * → confirmed_flush_lsn 照推进;新事务再读 → 0 rows
 * </pre>
 *
 * <p>契约(本测试钉住):
 * <ul>
 *   <li>drain 用 {@code pg_logical_slot_peek_changes}(非破坏读),不用 get_changes;</li>
 *   <li>一个事务在目标 commit + 本地位点落定之后,才调
 *       {@code pg_replication_slot_advance(slot, commitLsn)} 显式确认 —— 该调用效果
 *       同样不支持事务回滚(实证),故顺序不可提前;</li>
 *   <li>flush 失败 / 半事务:绝不 advance,下周期 peek 原样拿到同一事务,幂等重放;</li>
 *   <li>行数预算切断事务后再次 peek,同一事务会从头返回:BEGIN 时必须清掉半截缓冲,
 *       不得把同一行重复落两次;</li>
 *   <li>空事务(BEGIN 紧跟 COMMIT)也必须确认,否则 slot 永不前进、无限重复 peek。</li>
 * </ul>
 */
class PgLogicalCdcConsumerDrainTest {

    private static final String H2_URL =
            "jdbc:h2:mem:pg_drain;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    private Connection source;          // mock:slot 流由脚本驱动
    private PreparedStatement peekPs;
    private PreparedStatement advancePs;
    private final Deque<ResultSet> script = new ArrayDeque<>();
    private CdcPositionStore positionStore;
    private DataSourceManager dataSourceManager;
    private SyncProperties properties;
    private PgLogicalCdcConsumer consumer;

    private static ColumnMeta column(String name, int jdbcType, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(pk);
        return column;
    }

    /** 一次 peek 调用的返回行:[lsn, test_decoding 文本]。 */
    private void scriptCall(String[]... rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        // 每个 when() 必须独立完成再开下一个(Mockito 不允许嵌套未完成的 stubbing)。
        Boolean[] nexts = new Boolean[rows.length + 1];
        java.util.Arrays.fill(nexts, 0, rows.length, Boolean.TRUE);
        nexts[rows.length] = Boolean.FALSE;
        when(rs.next()).thenReturn(nexts[0],
                java.util.Arrays.copyOfRange(nexts, 1, nexts.length));
        if (rows.length > 0) {
            String[] lsns = new String[rows.length];
            String[] datas = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                lsns[i] = rows[i][0];
                datas[i] = rows[i][1];
            }
            when(rs.getString(1)).thenReturn(lsns[0],
                    java.util.Arrays.copyOfRange(lsns, 1, lsns.length));
            when(rs.getString(2)).thenReturn(datas[0],
                    java.util.Arrays.copyOfRange(datas, 1, datas.length));
        }
        script.add(rs);
    }

    /** 目标端每次开新连接 — flushTransaction 用完即 close,不能共享同一对象。 */
    private static Connection h2conn() throws SQLException {
        return DriverManager.getConnection(H2_URL, "sa", "");
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        }

        source = mock(Connection.class);
        when(source.getAutoCommit()).thenReturn(true);
        peekPs = mock(PreparedStatement.class);
        advancePs = mock(PreparedStatement.class);
        when(source.prepareStatement(contains("peek_changes"))).thenReturn(peekPs);
        when(source.prepareStatement(contains("slot_advance"))).thenReturn(advancePs);
        when(peekPs.executeQuery()).thenAnswer(inv -> script.poll());
        ResultSet advanceRs = mock(ResultSet.class);
        when(advanceRs.next()).thenReturn(true);
        when(advancePs.executeQuery()).thenReturn(advanceRs);

        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("id");
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(meta);

        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .sourceReader(reader)
                .connectionProvider(cfg -> {
                    try {
                        return h2conn();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();

        positionStore = mock(CdcPositionStore.class);
        dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnection(any())).thenReturn(source);
        properties = new SyncProperties();

        Project project = new Project();
        project.setId(1L);
        project.setName("pg-1");
        consumer = new PgLogicalCdcConsumer(project,
                mock(SyncLockService.LockHandle.class), ctx, positionStore,
                mock(CdcBaselineService.class), mock(SyncTaskStore.class),
                mock(SyncStateWriter.class), dataSourceManager,
                new CdcRuntimeStats(), properties);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    private int targetRowCount() throws SQLException {
        try (Connection c = h2conn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static final String BEGIN = "BEGIN 12345";
    private static final String COMMIT = "COMMIT 12345";
    private static String insert(long id, String name) {
        return "table public.orders: INSERT: id[bigint]:" + id + " name[text]:'" + name + "'";
    }

    @Test
    void successfulTransactionFlushesTargetThenAdvancesSlotToCommitLsn() throws Exception {
        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", insert(1L, "x")},
                new String[]{"0/1002", COMMIT});

        int total = consumer.drain(source, "jync_1");

        assertThat(total).isEqualTo(3);
        assertThat(targetRowCount()).isEqualTo(1);
        // 顺序钉死:目标落库 → 本地位点 → slot 确认。slot 位点取 COMMIT 行 LSN。
        InOrder order = inOrder(source, positionStore, advancePs);
        order.verify(source).setAutoCommit(false);
        order.verify(positionStore).advanceLsn(eq(1L), anyString());
        order.verify(advancePs).setString(eq(1), eq("jync_1"));
        order.verify(advancePs).setString(eq(2), eq("0/1002"));
        order.verify(advancePs).executeQuery();
        verify(source, never()).rollback();
    }

    @Test
    void targetFailureRollsBackAndNeverAdvancesSlot() throws Exception {
        // 目标端开不出连接 → drain 抛出;slot 必须原样不动,下周期 peek 重读同事务。
        SyncContext brokenCtx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .sourceReader(mock(MetadataReader.class))
                .connectionProvider(cfg -> { throw new SQLException("target down"); })
                .build();
        Project project = new Project();
        project.setId(1L);
        project.setName("pg-1");
        PgLogicalCdcConsumer broken = new PgLogicalCdcConsumer(project,
                mock(SyncLockService.LockHandle.class), brokenCtx, positionStore,
                mock(CdcBaselineService.class), mock(SyncTaskStore.class),
                mock(SyncStateWriter.class), dataSourceManager,
                new CdcRuntimeStats(), properties);
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("id");
        when(((MetadataReader) brokenCtx.getSourceReader())
                .readTable(any(), anyString(), anyString())).thenReturn(meta);
        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", insert(1L, "x")},
                new String[]{"0/1002", COMMIT});

        assertThatThrownBy(() -> broken.drain(source, "jync_1"))
                .isInstanceOf(SQLException.class);

        verify(advancePs, never()).executeQuery();
        verify(source).rollback();
        verify(positionStore, never()).advanceLsn(anyLong(), anyString());
        assertThat(targetRowCount()).isZero();
    }

    @Test
    void halfTransactionIsNeverFlushedAndSlotIsNotAdvanced() throws Exception {
        // peek 只拿到 BEGIN+INSERT(下一调用 slot 空):半事务不得落目标,不得 advance。
        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", insert(1L, "x")});
        scriptCall(); // 第二次 peek:slot 已空

        int total = consumer.drain(source, "jync_1");

        assertThat(total).isEqualTo(2);
        assertThat(targetRowCount()).isZero();
        verify(advancePs, never()).executeQuery();
        verify(source).rollback();
        verify(positionStore, never()).advanceLsn(anyLong(), anyString());
    }

    @Test
    void cutoffMidTransactionRepeeksWholeTransactionAndAdvancesAtCommit() throws Exception {
        // batchSize=2:首次 peek 到 BEGIN+INSERT 满预算;二次 peek 整个事务从头返回
        // (BEGIN 再来)——半截缓冲必须丢弃重建,id=1 不得落两次。
        properties.getCdc().getPg().setBatchSize(2);
        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", insert(1L, "x")});
        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", insert(1L, "x")},
                new String[]{"0/1002", insert(2L, "y")},
                new String[]{"0/1003", COMMIT});

        int total = consumer.drain(source, "jync_1");

        assertThat(total).isEqualTo(6);
        assertThat(targetRowCount()).isEqualTo(2);
        verify(peekPs, times(2)).executeQuery();
        verify(advancePs, times(1)).setString(eq(2), eq("0/1003"));
        verify(advancePs, times(1)).executeQuery();
        verify(source, never()).rollback();
    }

    @Test
    void emptyTransactionIsStillConfirmedSoTheSlotMovesForward() throws Exception {
        // BEGIN 紧跟 COMMIT、无数据行:不 advance 会让这个空事务每次 peek 都回来,
        // slot 永远不动。空事务也必须确认到它的 COMMIT 位点。
        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1002", COMMIT});

        int total = consumer.drain(source, "jync_1");

        assertThat(total).isEqualTo(2);
        assertThat(targetRowCount()).isZero();
        verify(advancePs, times(1)).setString(eq(2), eq("0/1002"));
        verify(advancePs, times(1)).executeQuery();
    }
}
