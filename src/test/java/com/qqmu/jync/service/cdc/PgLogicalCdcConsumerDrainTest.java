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
    private MetadataReader reader;
    private PgLogicalCdcConsumer consumer;
    private CdcRuntimeStats stats;
    private SyncConfig config;

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
        reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(meta);

        config = new SyncConfig();
        stats = new CdcRuntimeStats();
        SyncContext ctx = SyncContext.builder()
                .config(config)
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
                stats, properties);
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

    private static String delete(long id) {
        return "table public.orders: DELETE: id[bigint]:" + id;
    }

    /** 目标端现存主键(升序),用于断言事务净效果。 */
    private java.util.List<Long> targetIds() throws SQLException {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        try (Connection c = h2conn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM orders ORDER BY id")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    @Test
    void deleteThenInsertOfTheSameKeyWithinOneTransactionKeepsTheRow() throws Exception {
        // REPLACE 语义:同一事务里 DELETE 1 → INSERT 1,源端净效果是行存在。
        // flush 先应用全部 upsert 再应用全部 delete,不折叠时最后落的 DELETE 把刚写的行删掉。
        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", delete(1)},
                new String[]{"0/1002", insert(1L, "back")},
                new String[]{"0/1003", COMMIT});

        consumer.drain(source, "jync_1");

        assertThat(targetIds()).containsExactly(1L);
    }

    @Test
    void insertThenDeleteOfTheSameKeyWithinOneTransactionLeavesNoRow() throws Exception {
        // 反方向:INSERT 1 → DELETE 1,净效果是行不存在(折叠后只剩 delete)。
        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", insert(1L, "x")},
                new String[]{"0/1002", delete(1)},
                new String[]{"0/1003", COMMIT});

        consumer.drain(source, "jync_1");

        assertThat(targetIds()).isEmpty();
    }

    @Test
    void updateThatMovesThePrimaryKeyRemovesTheOldTargetRow() throws Exception {
        // 主键 1→2:old-key 镜像必须变成旧主键的 DELETE,否则目标端同时留下 1 和 2。
        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", insert(1L, "a")},
                new String[]{"0/1002", "table public.orders: UPDATE:"
                        + " old-key: id[bigint]:1 name[text]:'a'"
                        + " new-tuple: id[bigint]:2 name[text]:'a'"},
                new String[]{"0/1003", COMMIT});

        consumer.drain(source, "jync_1");

        assertThat(targetIds()).containsExactly(2L);
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

    @Test
    void aColumnAddedOnTheSourceAfterTheBindingWasCachedStillLands() throws Exception {
        // 逻辑复制槽的流里没有 DDL 事件(test_decoding 只发行变更),消费者构建 binding 时
        // 读一次源端元数据后永久缓存。源库 ADD COLUMN 之后,新行的 INSERT 带着新列,而
        // 旧 binding 的列清单里没有它 —— upsert 语句整句都不含该列,值静默丢失,既不报错
        // 也不计数,永远不会自愈。行形状与缓存元数据不一致是唯一的信号:必须借此重读。
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE orders ADD COLUMN note VARCHAR(64)");
        }
        TableMeta withNote = new TableMeta();
        withNote.setName("orders");
        withNote.getColumns().add(column("id", Types.BIGINT, true));
        withNote.getColumns().add(column("name", Types.VARCHAR, false));
        withNote.getColumns().add(column("note", Types.VARCHAR, false));
        withNote.getPrimaryKeys().add("id");
        // 第一次读(构建缓存)是 DDL 之前的形状,之后是 DDL 之后的形状。
        when(reader.readTable(any(), anyString(), anyString()))
                .thenReturn(ordersMeta(), withNote);

        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", "table public.orders: INSERT: id[bigint]:1"
                        + " name[text]:'a' note[text]:'hello'"},
                new String[]{"0/1002", COMMIT});

        consumer.drain(source, "jync_1");

        try (Connection c = h2conn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT note FROM orders WHERE id = 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("hello");
        }
        verify(reader, times(2)).readTable(any(), anyString(), anyString());
    }

    @Test
    void aColumnDroppedOnTheSourceStopsBeingNulledOnTheTarget() throws Exception {
        // 反方向:源库 DROP COLUMN 之后,行里不再有该列。旧 binding 仍按旧列清单构建
        // upsert —— 缺失的列被绑成 NULL,目标端已有的值被凭空抹掉。行形状不一致同样是
        // 唯一信号:刷新后 upsert 只触及仍然存在的列。
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE orders ADD COLUMN note VARCHAR(64)");
            st.execute("INSERT INTO orders (id, name, note) VALUES (1, 'a', 'keep me')");
        }
        TableMeta withNote = new TableMeta();
        withNote.setName("orders");
        withNote.getColumns().add(column("id", Types.BIGINT, true));
        withNote.getColumns().add(column("name", Types.VARCHAR, false));
        withNote.getColumns().add(column("note", Types.VARCHAR, false));
        withNote.getPrimaryKeys().add("id");
        when(reader.readTable(any(), anyString(), anyString()))
                .thenReturn(withNote, ordersMeta());

        scriptCall(
                new String[]{"0/1000", BEGIN},
                new String[]{"0/1001", "table public.orders: UPDATE:"
                        + " id[bigint]:1 name[text]:'renamed'"},
                new String[]{"0/1002", COMMIT});

        consumer.drain(source, "jync_1");

        try (Connection c = h2conn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, note FROM orders WHERE id = 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("renamed");
            assertThat(rs.getString(2)).isEqualTo("keep me");
        }
        verify(reader, times(2)).readTable(any(), anyString(), anyString());
    }

    /** 未修改的 TOAST 列:test_decoding 打印占位符,真实值不在流里。 */
    private static String toastUpdate(long id, String name) {
        return "table public.orders: UPDATE: id[bigint]:" + id
                + " name[text]:'" + name + "' body[text]:unchanged-toast-datum";
    }

    @Test
    void unchangedToastRowsAreCountedPerTableLikeLogMinersMissingLobs() throws Exception {
        // 值确实不在流里(test_decoding 无法还原未修改的 TOAST 列),跳过是唯一诚实的
        // 选择 —— 策略不变,仍计入 strike-out。但它们必须和 LogMiner 的行外 LOB 一样
        // 进入按表的 missing-Lob 计数(CDC 状态面板可见),而不是只落进泛化的
        // "unsupported skipped";否则运维看不出是哪张表、为什么丢。
        scriptCall(
                new String[]{"0/2000", BEGIN},
                new String[]{"0/2001", toastUpdate(1, "a")},
                new String[]{"0/2002", toastUpdate(1, "b")},
                new String[]{"0/2003", toastUpdate(1, "c")},
                new String[]{"0/2004", COMMIT});

        consumer.drain(source, "jync_1");

        assertThat(stats.getSkippedMissingLob()).isEqualTo(3);
        assertThat(stats.missingLobByTable()).containsEntry("public.orders", 3L);
    }

    @Test
    void toastRowsOfTablesOutsideTheProjectAreNotCounted() throws Exception {
        // 槽解码整个数据库:其他表的 TOAST 行与本项目无关,既不是本项目的丢数,
        // 也不该把通用计数推高(更不该参与 strike-out)。
        config.getTables().add("orders");
        scriptCall(
                new String[]{"0/2000", BEGIN},
                new String[]{"0/2001", "table public.audit_log: UPDATE: id[bigint]:1"
                        + " body[text]:unchanged-toast-datum"},
                new String[]{"0/2002", COMMIT});

        consumer.drain(source, "jync_1");

        assertThat(stats.getSkippedMissingLob()).isZero();
        assertThat(stats.snapshot().get("skippedUnsupported")).isEqualTo(0L);
    }

    /** 目标表 orders 的原始形状:id 主键 + name。 */
    private static TableMeta ordersMeta() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("id");
        return meta;
    }

    @Test
    void aTableWithoutAPrimaryKeyIsInspectedOnceNotOncePerRow() throws Exception {
        // 无主键表的事件无法应用(BindingSupplier 返回 null),但空结果不进缓存:
        // 每一条事件都要重新开一个源连接、重读一遍元数据、再打一行同样的 WARN。
        // 一条无主键的热表就是连接池 + 日志的双重风暴。SqlServerCdcConsumer 已用
        // 哨兵缓存空结果解决同一问题,PG 这里漏了。
        TableMeta noPk = new TableMeta();
        noPk.setName("nopk");
        noPk.getColumns().add(column("id", Types.BIGINT, false));
        when(reader.readTable(any(), eq("public"), eq("nopk"))).thenReturn(noPk);

        scriptCall(
                new String[]{"0/2000", BEGIN},
                new String[]{"0/2001", "table public.nopk: INSERT: id[bigint]:1"},
                new String[]{"0/2002", "table public.nopk: INSERT: id[bigint]:2"},
                new String[]{"0/2003", COMMIT});

        consumer.drain(source, "jync_1");

        verify(reader, times(1)).readTable(any(), eq("public"), eq("nopk"));
    }
}
