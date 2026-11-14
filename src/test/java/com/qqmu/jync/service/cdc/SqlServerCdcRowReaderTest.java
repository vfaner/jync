package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * 一个 capture instance 的变更表窗口动辄几十万行。旧的 readRow 对每一行、每一列都做
 * {@code col.toLowerCase}、{@code meta.column(col)}、{@code rs.findColumn(col)} 和按列名
 * 的 {@code getObject(String)} —— 列名查找是 O(列数),整行就是 O(列数²),而这些结果在整个
 * ResultSet 上是常量。RowReader 在窗口的第一行把这些解析一次,之后按索引取值。
 */
class SqlServerCdcRowReaderTest {

    private Connection conn;
    private ResultSet rs;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection(
                "jdbc:h2:mem:sqlserver_row_reader;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE \"orders\" (\"ID\" BIGINT PRIMARY KEY,"
                    + " \"BODY\" VARCHAR(64), \"NOTE\" VARCHAR(64))");
            st.execute("INSERT INTO \"orders\" VALUES (1, 'a', 'first')");
            st.execute("INSERT INTO \"orders\" VALUES (2, 'b', 'second')");
        }
        Statement st = conn.createStatement();
        rs = st.executeQuery("SELECT \"ID\", \"BODY\", \"NOTE\" FROM \"orders\" ORDER BY \"ID\"");
        rs.next();
    }

    @AfterEach
    void tearDown() throws SQLException {
        rs.close();
        try (Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        conn.close();
    }

    /** 行内容完全由 binding 决定;CT 结果集里其余的列(__$start_lsn 等)不进入行。 */
    @Test
    void readsTheBindingColumnsByIndexAndIgnoresTheRestOfTheChangeTable() throws SQLException {
        SqlServerCdcConsumer.RowReader reader =
                SqlServerCdcConsumer.RowReader.of(rs, binding(), Set.of("id", "body", "note"));

        Map<String, Object> row = reader.read(rs);

        assertThat(row).containsExactly(
                Map.entry("ID", 1L), Map.entry("BODY", "a"), Map.entry("NOTE", "first"));
    }

    /** 捕获实例没有记录的列不得写进行:applier 会把缺失键当成显式 NULL 写下去。 */
    @Test
    void aColumnTheCaptureInstanceDoesNotCarryIsLeftAbsent() throws SQLException {
        SqlServerCdcConsumer.RowReader reader =
                SqlServerCdcConsumer.RowReader.of(rs, binding(), Set.of("id", "body"));

        assertThat(reader.read(rs)).doesNotContainKey("NOTE");
    }

    /** findColumn 是列名线性查找;整个窗口只解析一次,而不是每行每列一次。 */
    @Test
    void resolvesTheColumnIndexesOnceForTheWholeWindow() throws SQLException {
        AtomicInteger lookups = new AtomicInteger();
        ResultSet counted = (ResultSet) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                new CountingHandler(rs, lookups));
        SqlServerCdcConsumer.RowReader reader =
                SqlServerCdcConsumer.RowReader.of(counted, binding(), Set.of("id", "body"));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(reader.read(counted));
        counted.next();
        rows.add(reader.read(counted));

        assertThat(rows).containsExactly(
                Map.of("ID", 1L, "BODY", "a"), Map.of("ID", 2L, "BODY", "b"));
        assertThat(lookups).hasValue(2);
    }

    /** 不适用表(或绑定缺失)在建计划时就返回 null,扫描循环继续按行计数。 */
    @Test
    void anUnsupportedBindingHasNoRowPlan() throws SQLException {
        assertThat(SqlServerCdcConsumer.RowReader.of(rs, null, Set.of("id"))).isNull();
    }

    /**
     * Delete and update-before images never reach the target whole — the fold keeps only
     * their primary keys — so their reader must not pay for the rest of the payload (LOBs
     * included). PK names are matched case-insensitively against the binding's columns.
     */
    @Test
    void aKeysOnlyReaderReadsOnlyThePrimaryKeyColumns() throws SQLException {
        SqlServerCdcConsumer.RowReader reader = SqlServerCdcConsumer.RowReader.of(
                rs, binding(), Set.of("id", "body", "note"), Set.of("id"));

        assertThat(reader.read(rs)).containsExactly(Map.entry("ID", 1L));
    }

    /** The image's __$operation decides which reader it needs. */
    @Test
    void deleteAndUpdateBeforeImagesUseTheKeysOnlyReader() throws SQLException {
        SqlServerCdcConsumer.RowReader full =
                SqlServerCdcConsumer.RowReader.of(rs, binding(), Set.of("id", "body", "note"));
        SqlServerCdcConsumer.RowReader keys =
                SqlServerCdcConsumer.RowReader.of(rs, binding(), Set.of("id", "body", "note"),
                        Set.of("ID"));

        assertThat(SqlServerCdcConsumer.readerFor(1, full, keys)).isSameAs(keys);
        assertThat(SqlServerCdcConsumer.readerFor(3, full, keys)).isSameAs(keys);
        assertThat(SqlServerCdcConsumer.readerFor(2, full, keys)).isSameAs(full);
        assertThat(SqlServerCdcConsumer.readerFor(4, full, keys)).isSameAs(full);
    }

    private static CdcTableBinding binding() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(column("ID", Types.BIGINT, true));
        meta.getColumns().add(column("BODY", Types.VARCHAR, false));
        meta.getColumns().add(column("NOTE", Types.VARCHAR, false));
        meta.getPrimaryKeys().add("ID");
        return CdcTableBinding.of("dbo", "orders", meta, "orders", null);
    }

    private static ColumnMeta column(String name, int jdbcType, boolean pk) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setPrimaryKey(pk);
        return column;
    }

    /** Counts findColumn calls; every other call goes straight to the real ResultSet. */
    private static final class CountingHandler implements InvocationHandler {

        private final ResultSet target;
        private final AtomicInteger lookups;

        private CountingHandler(ResultSet target, AtomicInteger lookups) {
            this.target = target;
            this.lookups = lookups;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("findColumn".equals(method.getName())) {
                lookups.incrementAndGet();
            }
            return method.invoke(target, args);
        }
    }
}
