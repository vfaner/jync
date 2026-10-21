package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * B5 写入侧:CDC 行事件经方言 upsert / delete-by-pk 落目标库,与轮询写引擎同一套
 * SQL 约定(幂等 upsert 吸收重叠区)。applier 不开不关事务、不 commit——XID 边界的
 * 提交与位点持久化在 consumer/handler 层(先写数据后推位点)。
 */
class CdcEventApplierTest {

    private Connection target;
    private CdcEventApplier applier;
    private CdcTableBinding binding;
    private CdcRuntimeStats stats;

    private static ColumnMeta column(String name, int jdbcType, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(pk);
        return column;
    }

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:cdc_apply;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "");
        try (Statement st = target.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))");
        }
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getColumns().add(column("amount", Types.DECIMAL, false));
        meta.getPrimaryKeys().add("id");
        binding = CdcTableBinding.of("src", "orders", meta, "orders", null);
        stats = new CdcRuntimeStats();
        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(2)
                .build();
        applier = new CdcEventApplier(ctx, stats);
        target.setAutoCommit(false);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = target.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        target.close();
    }

    private static Map<String, Object> row(long id, String name, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("amount", amount == null ? null : new java.math.BigDecimal(amount));
        return row;
    }

    private List<Map<String, Object>> rows() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name, amount FROM orders ORDER BY id")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                row.put("amount", rs.getBigDecimal("amount"));
                out.add(row);
            }
        }
        return out;
    }

    @Test
    void upsertsInsertThenUpdateIdempotently() throws SQLException {
        applier.applyUpserts(target, binding, List.of(row(1L, "a", "1.50"), row(2L, "b", "2.50")));
        target.commit();
        assertThat(rows()).containsExactly(row(1L, "a", "1.50"), row(2L, "b", "2.50"));

        // 同一行再放一次(重叠区回放)= 幂等更新,不翻倍
        applier.applyUpserts(target, binding, List.of(row(1L, "a2", "9.99")));
        target.commit();
        assertThat(rows()).containsExactly(row(1L, "a2", "9.99"), row(2L, "b", "2.50"));
        assertThat(stats.snapshot().get("rowsApplied")).isEqualTo(3L);
    }

    @Test
    void deletesRemoveByPkAndTolerateMissingRows() throws SQLException {
        applier.applyUpserts(target, binding, List.of(row(1L, "a", null), row(2L, "b", null)));
        target.commit();

        applier.applyDeletes(target, binding, List.of(row(1L, null, null), row(404L, null, null)));
        target.commit();
        assertThat(rows()).containsExactly(row(2L, "b", null));
    }

    @Test
    void appliedRowsAccumulatePerSourceTableForTheChangeLogWindow() throws SQLException {
        // 变更日志按表聚合用的是绑定里的源表名(binding.getTable()),不是目标表名;
        // upsert/delete 分两个计数,空批次不产生窗口。
        applier.applyUpserts(target, binding, List.of(row(1L, "a", null), row(2L, "b", null)));
        applier.applyDeletes(target, binding, List.of(row(1L, null, null)));
        applier.applyUpserts(target, binding, List.of());
        applier.applyDeletes(target, binding, List.of());
        target.commit();

        List<CdcRuntimeStats.TableChange> windows = stats.drainAllWindows();
        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).table()).isEqualTo("orders");
        assertThat(windows.get(0).upserts()).isEqualTo(2L);
        assertThat(windows.get(0).deletes()).isEqualTo(1L);
    }

    @Test
    void batchSizeFlushesMidwayWithoutCommitting() throws SQLException {
        // batchSize=2:5 行 → 中途两次 executeBatch,但未 commit 前目标库读不到
        List<Map<String, Object>> five = List.of(row(1L, "a", null), row(2L, "b", null),
                row(3L, "c", null), row(4L, "d", null), row(5L, "e", null));
        applier.applyUpserts(target, binding, five);
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            // 同连接内可见(未提交事务读自身),commit 后才对外可见
            assertThat(rs.getInt(1)).isEqualTo(5);
        }
        target.rollback();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero(); // rollback 完整回滚整个事务
        }
    }

    @Test
    void aNotNullViolationIsNotMistakenForAUniqueConflict() throws SQLException {
        // 目标比源端更严格:INSERT 撞 NOT NULL(23502)曾被当成"行已存在"重试 UPDATE,
        // UPDATE 命中 0 行后这行就无声消失了。必须把约束错误抛给 consumer,让位点不前进。
        try (Statement st = target.createStatement()) {
            st.execute("ALTER TABLE orders ALTER COLUMN name SET NOT NULL");
        }

        assertThatThrownBy(() -> applier.applyUpserts(target, binding,
                List.of(row(1L, null, "1.00"))))
                .isInstanceOf(SQLException.class);
    }

    // —— 原生 upsert 方言(MySQL 等生产主路径)走批量 executeBatch 分支 ——

    private CdcEventApplier applierWithNativeUpsert(CdcRuntimeStats nativeStats) {
        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new MergeDialect())
                .batchSize(2)
                .build();
        return new CdcEventApplier(ctx, nativeStats);
    }

    @Test
    void nativeUpsertsBatchInsertThenUpdateIdempotently() throws SQLException {
        CdcRuntimeStats nativeStats = new CdcRuntimeStats();
        CdcEventApplier nativeApplier = applierWithNativeUpsert(nativeStats);

        // batchSize=2,3 行 → 中途 executeBatch + 尾批;MERGE 单语句即幂等 upsert
        nativeApplier.applyUpserts(target, binding,
                List.of(row(1L, "a", "1.50"), row(2L, "b", "2.50"), row(3L, "c", "3.50")));
        target.commit();
        assertThat(rows()).containsExactly(
                row(1L, "a", "1.50"), row(2L, "b", "2.50"), row(3L, "c", "3.50"));

        // 同一行再放一次(重叠区回放)= 幂等更新,不翻倍——且走的是原生批量分支
        nativeApplier.applyUpserts(target, binding, List.of(row(1L, "a2", "9.99")));
        target.commit();
        assertThat(rows()).containsExactly(
                row(1L, "a2", "9.99"), row(2L, "b", "2.50"), row(3L, "c", "3.50"));
        assertThat(nativeStats.snapshot().get("rowsApplied")).isEqualTo(4L);
    }

    @Test
    void nativeBatchFlushesMidwayAndRollbackDiscardsAll() throws SQLException {
        CdcRuntimeStats nativeStats = new CdcRuntimeStats();
        CdcEventApplier nativeApplier = applierWithNativeUpsert(nativeStats);

        // batchSize=2,3 行 → 两次 executeBatch(2+1);executeBatch 不得隐含 commit
        nativeApplier.applyUpserts(target, binding,
                List.of(row(1L, "a", null), row(2L, "b", null), row(3L, "c", null)));
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(3); // 同连接内可见(未提交事务读自身)
        }
        target.rollback();
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero(); // 一次 rollback 完整回退两个批次
        }
    }

    // —— R3(T8 裁定落地):查询超时与 DataSyncService 同源(SyncProperties.queryTimeoutSeconds),
    //    经 3 参构造传入,applier 对每条语句调 JdbcUtil.applyQueryTimeout;2 参构造保持无超时 ——

    @Test
    void threeArgConstructorAppliesTheQueryTimeoutToStatements() throws SQLException {
        SyncContext timedCtx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(2)
                .build();
        CdcEventApplier timed = new CdcEventApplier(timedCtx, new CdcRuntimeStats(), 45);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        timed.applyDeletes(conn, binding, List.of(row(1L, null, null)));

        verify(ps).setQueryTimeout(45);
    }

    @Test
    void twoArgConstructorDelegatesWithNoTimeout() throws SQLException {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);

        applier.applyDeletes(conn, binding, List.of(row(1L, null, null))); // setUp 的 2 参构造

        verify(ps, never()).setQueryTimeout(anyInt());
    }

    /**
     * 原生 upsert 目标的替身:H2 的 {@code MERGE INTO ... KEY(...)} 与 MySQL 的
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} 同为单语句幂等 upsert;
     * {@code supportsNativeUpsert()=true} 使 applier 走 executeBatch 批量分支
     * 而非 Generic 的逐行 update-then-insert 仿真。
     */
    private static final class MergeDialect extends GenericSqlDialect {
        @Override
        public String getUpsertSql(String schema, String table, List<String> columns,
                                   List<String> pkColumns) {
            return "MERGE INTO " + qualify(schema, table) + " (" + quotedList(columns)
                    + ") KEY (" + quotedList(pkColumns) + ") VALUES ("
                    + placeholders(columns.size()) + ")";
        }

        @Override
        public boolean supportsNativeUpsert() {
            return true;
        }
    }
}
