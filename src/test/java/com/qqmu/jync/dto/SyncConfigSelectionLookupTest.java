package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * 同一个配置会被多个线程共享(CDC 每事件路径)。索引的发布对读者必须原子:旧实现用
     * 三个非 volatile 字段先发布 lower 再发布守卫,读者可能拿着 null 的 lower 却看到已完成
     * 的守卫字段,跳过重建直接 NPE。每一轮都从全新配置开始,反复打开「首次发布」的竞态窗口。
     */
    @Test
    void racingLookupsNeverSeeAHalfPublishedIndex() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int round = 0; round < 500 && failure.get() == null; round++) {
            SyncConfig config = new SyncConfig();
            config.setTables(new LinkedHashSet<>(List.of("orders")));
            // 四个读者同时做第一次查询:谁先建好索引,其余三个就可能读到「守卫已发布、
            // lower 还是 null」的半完成状态 —— 每一轮都是一次全新的竞态窗口。
            CyclicBarrier start = new CyclicBarrier(4);
            Thread[] readers = new Thread[4];
            for (int t = 0; t < readers.length; t++) {
                readers[t] = new Thread(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 5_000 && failure.get() == null; i++) {
                            config.includesTable("orders");
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
                readers[t].start();
            }
            for (Thread reader : readers) {
                reader.join(10_000);
            }
        }
        assertThat(failure.get()).isNull();
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
