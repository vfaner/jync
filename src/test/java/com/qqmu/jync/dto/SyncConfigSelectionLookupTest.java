package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * 选择集在热路径上被反复查询:MySQL binlog 的 TABLE_MAP 几乎每个事务都发,每一次都要
 * {@code includesTable} 扫描整个选择集做 equalsIgnoreCase —— 选中几百张表时是 O(表数²)。
 * 查询改为委托给一次性构建的大小写无关索引,同时必须保证配置变更后立即生效。
 */
class SyncConfigSelectionLookupTest {

    /**
     * 关键契约:选择集只在第一次查询时被遍历一次(建索引),之后每次查询都是常数时间。
     * 未建索引的实现在三次查询里会遍历三次 —— 这正是 binlog TABLE_MAP 热路径上的开销。
     */
    @Test
    void theSelectionIsScannedOnceAndThenLookedUpDirectly() {
        CountingSet tables = new CountingSet(List.of("orders", "customers"));
        SyncConfig config = new SyncConfig();
        config.setTables(tables);

        assertThat(config.includesTable("missing")).isFalse();
        assertThat(config.includesTable("CUSTOMERS")).isTrue();
        assertThat(config.includesTable("orders")).isTrue();

        assertThat(tables.iterations).isEqualTo(1);
    }

    @Test
    void selectionLookupsIgnoreCase() {
        SyncConfig config = new SyncConfig();
        config.setTables(new LinkedHashSet<>(List.of("Orders", "customers")));

        assertThat(config.includesTable("ORDERS")).isTrue();
        assertThat(config.includesTable("orders")).isTrue();
        assertThat(config.includesTable("customers")).isTrue();
        assertThat(config.includesTable("invoices")).isFalse();
    }

    /** 保存配置会整体替换选择集:索引必须跟着换,不能记住上一个项目/上一次保存的选择。 */
    @Test
    void replacingTheSelectionTakesEffectImmediately() {
        SyncConfig config = new SyncConfig();
        config.setTables(new LinkedHashSet<>(List.of("orders")));
        assertThat(config.includesTable("ORDERS")).isTrue();

        config.setTables(new LinkedHashSet<>(List.of("invoices")));

        assertThat(config.includesTable("INVOICES")).isTrue();
        assertThat(config.includesTable("ORDERS")).isFalse();
    }

    @Test
    void replacingWithASameSizedSelectionAlsoTakesEffect() {
        SyncConfig config = new SyncConfig();
        config.setTables(new LinkedHashSet<>(List.of("orders")));
        assertThat(config.includesTable("orders")).isTrue();

        config.setTables(Set.of("customers"));

        assertThat(config.includesTable("customers")).isTrue();
        assertThat(config.includesTable("orders")).isFalse();
    }

    /** 直接往 get 到的集合里加表(集成测试与脚本的写法)也要被看到。 */
    @Test
    void anInPlaceAdditionIsSeen() {
        SyncConfig config = new SyncConfig();
        config.setTables(new LinkedHashSet<>(List.of("orders")));
        assertThat(config.includesTable("orders")).isTrue();

        config.getTables().add("invoices");

        assertThat(config.includesTable("invoices")).isTrue();
    }

    /** 视图/存储过程各有独立索引,不能互相串。 */
    @Test
    void eachKindHasItsOwnLookup() {
        SyncConfig config = new SyncConfig();
        config.setViews(new LinkedHashSet<>(List.of("v_orders")));
        config.setProcedures(new LinkedHashSet<>(List.of("p_nightly")));

        assertThat(config.includesView("V_ORDERS")).isTrue();
        assertThat(config.includesView("P_NIGHTLY")).isFalse();
        assertThat(config.includesProcedure("P_NIGHTLY")).isTrue();
        assertThat(config.includesProcedure("V_ORDERS")).isFalse();
    }

    /** 索引字段是派生状态,不得进入持久化 JSON(它是 @Getter 生成的属性)。 */
    @Test
    void theLookupIsNotSerialised() throws Exception {
        SyncConfig config = new SyncConfig();
        config.setTables(new LinkedHashSet<>(List.of("orders")));
        config.includesTable("ORDERS");

        String json = new ObjectMapper().writeValueAsString(config);

        assertThat(json).doesNotContain("Lookup").doesNotContain("lookup");
    }

    /** Sequence whose traversal is observable, so the test can count full scans. */
    private static final class CountingSet extends AbstractSet<String> {

        private final List<String> values;
        private int iterations;

        private CountingSet(List<String> values) {
            this.values = values;
        }

        @Override
        public Iterator<String> iterator() {
            iterations++;
            return values.iterator();
        }

        @Override
        public int size() {
            return values.size();
        }
    }
}
