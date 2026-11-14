package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
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
 * 每个源事务都要重建的 upsert/insert/update/delete 语句、绑定顺序、类型映射与列清洗器,
 * 在一个 consumer run 内只该构建一次:它们全部是 binding(布局 + 主键)、目标方言
 * 与运行配置的纯函数。布局、主键或投影变化时必须换计划,不能复用旧 SQL。
 */
class CdcWritePlanCacheTest {

    private Connection target;
    private CountingDialect dialect;
    private SyncContext ctx;
    private CdcRuntimeStats stats;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:cdc_write_plan;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = target.createStatement()) {
            // Quoted: the dialect quotes identifiers when it qualifies names, and H2
            // stores an unquoted name upper-cased.
            st.execute("CREATE TABLE \"orders\" (\"id\" BIGINT PRIMARY KEY,"
                    + " \"name\" VARCHAR(64), \"amount\" DECIMAL(10,2))");
        }
        dialect = new CountingDialect();
        stats = new CdcRuntimeStats();
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(dialect)
                .batchSize(2)
                .build();
        target.setAutoCommit(false);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement st = target.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        target.close();
    }

    private static TableMeta orders(String... extraColumns) {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("id", Types.BIGINT, true));
        meta.getColumns().add(column("name", Types.VARCHAR, false));
        meta.getColumns().add(column("amount", Types.DECIMAL, false));
        for (String extra : extraColumns) {
            meta.getColumns().add(column(extra, Types.VARCHAR, false));
        }
        meta.getPrimaryKeys().add("id");
        return meta;
    }

    private static ColumnMeta column(String name, int jdbcType, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(pk);
        return column;
    }

    private static CdcTableBinding binding(TableMeta meta) {
        return CdcTableBinding.of("src", "orders", meta, "orders", null);
    }

    private static Map<String, Object> row(long id, String name, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("amount", amount == null ? null : new java.math.BigDecimal(amount));
        return row;
    }

    @Test
    void aSecondTransactionReusesTheStatementsBuiltForTheFirst() throws SQLException {
        CdcWritePlanCache cache = new CdcWritePlanCache(ctx);
        CdcEventApplier applier = new CdcEventApplier(ctx, stats, 0, cache);
        CdcTableBinding binding = binding(orders());

        applier.applyUpserts(target, binding, List.of(row(1L, "a", "1.00")));
        applier.applyUpserts(target, binding, List.of(row(2L, "b", "2.00")));
        applier.applyDeletes(target, binding, List.of(row(1L, null, null)));

        assertThat(dialect.insertStatements).isEqualTo(1);
        assertThat(dialect.updateStatements).isEqualTo(1);
        assertThat(dialect.deleteStatements).isEqualTo(1);
        assertThat(rows()).containsExactly(row(2L, "b", "2.00"));
    }

    @Test
    void aRebuiltBindingWithANewLayoutGetsItsOwnPlan() {
        CdcWritePlanCache cache = new CdcWritePlanCache(ctx);
        CdcTableBinding v1 = binding(orders());
        CdcTableBinding v2 = binding(orders("note"));

        var first = cache.upsertPlan(v1, v1.columnNames());
        var again = cache.upsertPlan(v1, v1.columnNames());
        var second = cache.upsertPlan(v2, v2.columnNames());

        assertThat(again).isSameAs(first);
        assertThat(second).isNotSameAs(first);
        assertThat(second.sql).contains("note");
    }

    @Test
    void aProjectedColumnListKeepsItsOwnPlanAlongsideTheFullOne() {
        CdcWritePlanCache cache = new CdcWritePlanCache(ctx);
        CdcTableBinding binding = binding(orders());

        var full = cache.upsertPlan(binding, List.of("id", "name", "amount"));
        var projected = cache.upsertPlan(binding, List.of("id", "name"));

        assertThat(projected.sql).doesNotContain("amount");
        assertThat(cache.upsertPlan(binding, List.of("id", "name", "amount"))).isSameAs(full);
    }

    @Test
    void aChangedPrimaryKeyRebuildsThePlanEvenThoughTheLayoutIsUnchanged() {
        CdcWritePlanCache cache = new CdcWritePlanCache(ctx);
        CdcTableBinding byId = binding(orders());
        TableMeta keyedByName = orders();
        keyedByName.getPrimaryKeys().clear();
        keyedByName.getPrimaryKeys().add("name");
        CdcTableBinding byName = binding(keyedByName);

        assertThat(byName.getLayoutSignature()).isEqualTo(byId.getLayoutSignature());

        var first = cache.upsertPlan(byId, byId.columnNames());
        var second = cache.upsertPlan(byName, byName.columnNames());

        assertThat(second).isNotSameAs(first);
        assertThat(second.pkColumns).containsExactly("name");
    }

    @Test
    void theColumnCleanserIsCompiledOncePerTable() {
        CdcWritePlanCache cache = new CdcWritePlanCache(ctx);

        assertThat(cache.cleanser("orders")).isSameAs(cache.cleanser("orders"));
    }

    private List<Map<String, Object>> rows() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = target.createStatement();
             var rs = st.executeQuery(
                     "SELECT \"id\", \"name\", \"amount\" FROM \"orders\" ORDER BY \"id\"")) {
            while (rs.next()) {
                out.add(row(rs.getLong("id"), rs.getString("name"), rs.getBigDecimal("amount").toPlainString()));
            }
        }
        return out;
    }

    /** Counts the statements a run would otherwise rebuild on every source transaction. */
    private static final class CountingDialect extends GenericSqlDialect {

        private int insertStatements;
        private int updateStatements;
        private int deleteStatements;

        @Override
        public String getUpsertSql(String schema, String table, List<String> columns,
                                   List<String> pkColumns) {
            insertStatements++;
            return super.getUpsertSql(schema, table, columns, pkColumns);
        }

        @Override
        public String getUpdateByPkSql(String schema, String table, List<String> columns,
                                       List<String> pkColumns) {
            updateStatements++;
            return super.getUpdateByPkSql(schema, table, columns, pkColumns);
        }

        @Override
        public String getDeleteByPkSql(String schema, String table, List<String> pkColumns) {
            deleteStatements++;
            return super.getDeleteByPkSql(schema, table, pkColumns);
        }
    }
}
