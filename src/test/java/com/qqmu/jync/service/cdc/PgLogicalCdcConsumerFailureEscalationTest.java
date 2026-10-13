package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * F-10(docker 实测 PG→openGauss):源库在停机期 ADD COLUMN 后,新行的 upsert 带着
 * 目标端不存在的列 —— openGauss 每轮拒绝(SQLSTATE 42703),该 SQLException 被
 * 分类为 TRANSIENT。旧行为:drain 失败 → 退避 → 再 drain 同样失败,消费者<b>空转
 * 无限循环</b>,既不落数据也不 park ERROR。MySQL 消费者靠「连续 N 个失败周期」的
 * strike-out 兜底;PG 消费者只有解析失败计数,目标 flush 失败永远不升级。
 *
 * <p>契约(本测试钉住):
 * <ul>
 *   <li>连续 {@code > 5} 个失败周期(无成功事务夹在中间)→ recordCdcFailure +
 *       park ERROR,线程结束(与 MySQL deserializationStrikeOut 同阈值同语义);</li>
 *   <li>一个成功落库的事务必须把连续失败计数清零 —— 3 败 + 1 成 + 3 败不到阈值,
 *       不得被老账 park(偶发抖动后长期健康运行的常态)。</li>
 * </ul>
 */
class PgLogicalCdcConsumerFailureEscalationTest {

    private static final String H2_URL =
            "jdbc:h2:mem:pg_escalation;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    private Connection source;
    private CdcPositionStore positionStore;
    private DataSourceManager dataSourceManager;
    private SyncTaskStore taskStore;
    private SyncProperties properties;
    private final AtomicLong nextId = new AtomicLong(1);

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

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        }

        source = mock(Connection.class);
        when(source.getAutoCommit()).thenReturn(true);
        PreparedStatement changesPs = mock(PreparedStatement.class);
        when(source.prepareStatement(anyString())).thenReturn(changesPs);
        // 每个 drain 周期都拉到一个全新的 BEGIN/INSERT/COMMIT 事务(模拟失败后 slot
        // 回卷,同一毒事务下周期再来;成功周期的行则带新 id 真正落库)。
        when(changesPs.executeQuery()).thenAnswer(inv -> oneTransactionResultSet());

        // ensureSlot:SELECT 说 slot 已存在,不建槽。
        Statement slotStmt = mock(Statement.class);
        ResultSet slotRs = mock(ResultSet.class);
        when(slotRs.next()).thenReturn(true);
        when(slotStmt.executeQuery(anyString())).thenReturn(slotRs);
        when(source.createStatement()).thenReturn(slotStmt);

        positionStore = mock(CdcPositionStore.class);
        CdcPosition anchored = mock(CdcPosition.class);
        when(anchored.getLsn()).thenReturn("0/1000");
        when(anchored.isBaselineDone()).thenReturn(true);
        when(positionStore.find(1L)).thenReturn(java.util.Optional.of(anchored));

        dataSourceManager = mock(DataSourceManager.class);
        when(dataSourceManager.getConnection(any())).thenReturn(source);

        taskStore = mock(SyncTaskStore.class);
        properties = new SyncProperties();
        properties.getCdc().getPg().setIdlePollMs(1);
    }

    /** BEGIN/INSERT/COMMIT 三行的一次性结果集,每行 id 取序列保证互不干扰。 */
    private ResultSet oneTransactionResultSet() throws SQLException {
        long id = nextId.getAndIncrement();
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getString(1)).thenReturn("0/2000", "0/2001", "0/2002");
        when(rs.getString(2)).thenReturn(
                "BEGIN 12345",
                "table public.orders: INSERT: id[bigint]:" + id
                        + " name[text]:'row-" + id + "'",
                "COMMIT 12345");
        return rs;
    }

    private TableMeta ordersMeta() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("id");
        return meta;
    }

    private PgLogicalCdcConsumer newConsumer(SyncContext ctx,
                                             SyncLockService.LockHandle lock) {
        Project project = new Project();
        project.setId(1L);
        project.setName("pg-1");
        return new PgLogicalCdcConsumer(project, lock, ctx, positionStore,
                mock(CdcBaselineService.class), taskStore,
                mock(SyncStateWriter.class), dataSourceManager,
                new CdcRuntimeStats(), properties);
    }

    /**
     * 按 drain 周期编排目标端:脚本用尽后保持最后一种状态。每个周期恰好一个事务,
     * 所以 open() 调用次数 == 周期数。
     */
    private SyncContext stagedTargetContext(final boolean... cycleSucceeds) throws SQLException {
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readTable(any(), anyString(), anyString())).thenReturn(ordersMeta());
        final AtomicInteger cycle = new AtomicInteger();
        return SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(100)
                .sourceReader(reader)
                .connectionProvider(cfg -> {
                    int n = cycle.getAndIncrement();
                    boolean ok = n < cycleSucceeds.length
                            ? cycleSucceeds[n]
                            : cycleSucceeds[cycleSucceeds.length - 1];
                    if (ok) {
                        return h2conn();
                    }
                    throw new SQLException("target column missing (42703)");
                })
                .build();
    }

    /** Lock renews for {@code grants} cycles, then refuses — bounds the loop in every path. */
    private static SyncLockService.LockHandle boundedLock(int grants) {
        AtomicInteger remaining = new AtomicInteger(grants);
        SyncLockService.LockHandle lock = mock(SyncLockService.LockHandle.class);
        when(lock.renew()).thenAnswer(inv -> remaining.decrementAndGet() >= 0);
        return lock;
    }

    private int targetRowCount() throws SQLException {
        try (Connection c = h2conn();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection c = h2conn(); Statement st = c.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void repeatedTargetFlushFailuresEscalateToAnErrorPark() throws Exception {
        // 目标每轮拒绝 → drain 每轮抛 TRANSIENT。第 6 个连续失败周期必须升级
        // (recordCdcFailure + park),run() 自行结束,而不是靠 lock 失效安静退出。
        PgLogicalCdcConsumer consumer = newConsumer(stagedTargetContext(
                false, false, false, false, false, false, false, false), boundedLock(20));

        consumer.run();

        verify(taskStore).recordCdcFailure(eq(1L), contains("target column missing"));
    }

    @Test
    void aSuccessfulTransactionResetsTheFailureEscalation() throws Exception {
        // 3 败 → 1 成 → 3 败(之后保持失败):任一连续段都不到阈值,lock 到期安静
        // 退出,全程绝不 park;且成功周期的行必须真正落库(证明确实 flush 成功)。
        PgLogicalCdcConsumer consumer = newConsumer(
                stagedTargetContext(false, false, false, true, false, false, false),
                boundedLock(8));

        consumer.run();

        verify(taskStore, never()).recordCdcFailure(any(), anyString());
        assertThat(targetRowCount()).isEqualTo(1);
    }
}
