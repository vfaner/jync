package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.converter.GenericSqlDialect;

/**
 * Deletion detection compares key sets read from two different databases, so a key value has
 * to be rendered into canonical text before it is compared. When the two drivers disagree
 * about the Java type — byte[] identity hashes, decimal scale, temporal classes — a naive
 * {@code String.valueOf} comparison misreads every target row as "deleted at source" and
 * wipes the table.
 */
class DataSyncServiceDeletionsTest {

    private Connection source;
    private Connection target;
    private DataSyncService service;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        source = DriverManager.getConnection("jdbc:h2:mem:del_src;DB_CLOSE_DELAY=-1", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:del_tgt;DB_CLOSE_DELAY=-1", "sa", "");
        service = new DataSyncService(new SyncProperties());
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        exec(source, "DROP ALL OBJECTS");
        exec(target, "DROP ALL OBJECTS");
        source.close();
        target.close();
    }

    @Test
    void binaryKeysMatchAcrossReads() throws SQLException {
        // byte[]'s default toString is an identity hash that differs on every read; without
        // normalization every target row would look deleted and the whole table would go.
        exec(source, "CREATE TABLE BIN_T (K VARBINARY(16) PRIMARY KEY)");
        exec(target, "CREATE TABLE BIN_T (K VARBINARY(16) PRIMARY KEY)");
        exec(source, "INSERT INTO BIN_T VALUES (X'0102'), (X'0304')");
        exec(target, "INSERT INTO BIN_T VALUES (X'0102'), (X'0304'), (X'0506')");

        int deleted = service.syncDeletions(source, target, table("BIN_T", "K", 3), ctx);

        // Only the key that is genuinely absent from the source may be removed.
        assertThat(deleted).isEqualTo(1);
        assertThat(count("BIN_T")).isEqualTo(2);
    }

    @Test
    void decimalScaleDriftIsNotADeletion() throws SQLException {
        // The same value arriving as 1.50 on one side and 1.5 on the other must compare equal.
        exec(source, "CREATE TABLE NUM_T (K NUMERIC(10,2) PRIMARY KEY)");
        exec(target, "CREATE TABLE NUM_T (K NUMERIC(10,1) PRIMARY KEY)");
        exec(source, "INSERT INTO NUM_T VALUES (1.50)");
        exec(target, "INSERT INTO NUM_T VALUES (1.5)");

        int deleted = service.syncDeletions(source, target, table("NUM_T", "K", 1), ctx);

        assertThat(deleted).isZero();
        assertThat(count("NUM_T")).isEqualTo(1);
    }

    @Test
    void rowsGenuinelyGoneFromSourceAreStillDeleted() throws SQLException {
        // Normalization must not turn the feature off: a real deletion still propagates.
        exec(source, "CREATE TABLE ORD (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE ORD (ID BIGINT PRIMARY KEY)");
        exec(source, "INSERT INTO ORD VALUES (1), (2)");
        exec(target, "INSERT INTO ORD VALUES (1), (2), (3)");

        int deleted = service.syncDeletions(source, target, table("ORD", "ID", 3), ctx);

        assertThat(deleted).isEqualTo(1);
        assertThat(count("ORD")).isEqualTo(2);
    }

    @Test
    void anUncheckedCommitFailureRollsBackThePendingDelete() throws SQLException {
        // 与写入路径同一个坑:删行循环同样关闭自动提交、最后恢复。恢复动作由 JDBC 驱动
        // 实现成"提交当前事务"——没有显式回滚时,一个未声明的 Runtime 异常(锁租约丢失、
        // 驱动在 commit 里抛未检查异常)会把已执行的删除悄悄留下。
        exec(source, "CREATE TABLE ORD (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE ORD (ID BIGINT PRIMARY KEY)");
        exec(source, "INSERT INTO ORD VALUES (1)");
        exec(target, "INSERT INTO ORD VALUES (1), (2), (3)");

        AtomicInteger rollbacks = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        Connection failingCommit = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("commit".equals(method.getName()) && commits.getAndIncrement() == 0) {
                        throw new IllegalStateException("driver blew up mid-delete");
                    }
                    if ("rollback".equals(method.getName())) {
                        rollbacks.incrementAndGet();
                    }
                    return method.invoke(target, args);
                });

        assertThatThrownBy(() -> service.syncDeletions(source, failingCommit,
                table("ORD", "ID", 3), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("driver blew up mid-delete");

        assertThat(rollbacks).as("the open transaction must be rolled back").hasValue(1);
        assertThat(count("ORD")).as("a failed delete batch must leave the rows in place")
                .isEqualTo(3);
    }

    /**
     * 超过内存上限的表不再整表读键,而是按主键分块比对:目标端按键序翻页,每块只拿这一块
     * 的主键回源库查在不在,内存占用从 O(整表) 降到 O(块)。1200 行走 3 个块,跨越块边界
     * 的删除必须一条不漏。
     */
    @Test
    void aTableAboveTheInMemoryCapIsComparedInChunks() throws SQLException {
        exec(source, "CREATE TABLE BIG_T (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE BIG_T (ID BIGINT PRIMARY KEY)");
        insertRange(source, "BIG_T", 1, 1000);
        insertRange(target, "BIG_T", 1, 1200);

        int deleted = service(chunkedProperties()).syncDeletions(source, target,
                table("BIG_T", "ID", 25_000L), ctx);

        assertThat(deleted).isEqualTo(200);
        assertThat(count("BIG_T")).isEqualTo(1000);
        assertThat(has("BIG_T", 1000)).isTrue();
        assertThat(has("BIG_T", 1001)).isFalse();
        assertThat(has("BIG_T", 1200)).isFalse();
    }

    /**
     * 边翻页边删除:第一块整块被删掉后,若翻页用的是 OFFSET 就会跳过紧随其后的行。
     * 键序翻页以上一块的最后一个主键为起点,删掉的行不会让游标错位。
     */
    @Test
    void deletingWhilePagingDoesNotSkipRows() throws SQLException {
        exec(source, "CREATE TABLE BIG_T (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE BIG_T (ID BIGINT PRIMARY KEY)");
        insertRange(source, "BIG_T", 201, 1099);
        insertRange(target, "BIG_T", 1, 1200);

        int deleted = service(chunkedProperties()).syncDeletions(source, target,
                table("BIG_T", "ID", 25_000L), ctx);

        assertThat(deleted).isEqualTo(301);
        assertThat(count("BIG_T")).isEqualTo(899);
        assertThat(has("BIG_T", 200)).isFalse();
        assertThat(has("BIG_T", 201)).isTrue();
        assertThat(has("BIG_T", 1099)).isTrue();
        assertThat(has("BIG_T", 1100)).isFalse();
    }

    /** 复合主键的分块比对:回源查在不在要按整行主键匹配,不能只看第一列。 */
    @Test
    void chunkedComparisonMatchesCompositeKeys() throws SQLException {
        exec(source, "CREATE TABLE PAIR (A INT, B INT, PRIMARY KEY (A, B))");
        exec(target, "CREATE TABLE PAIR (A INT, B INT, PRIMARY KEY (A, B))");
        exec(source, "INSERT INTO PAIR VALUES (1, 1), (1, 2), (2, 1)");
        exec(target, "INSERT INTO PAIR VALUES (1, 1), (1, 2), (2, 1), (2, 2), (3, 1)");

        int deleted = service(chunkedProperties()).syncDeletions(source, target,
                table("PAIR", List.of("A", "B"), 25_000L), ctx);

        assertThat(deleted).isEqualTo(2);
        assertThat(countRows("PAIR")).isEqualTo(3);
        assertThat(has("PAIR", "A = 2 AND B = 2")).isFalse();
        assertThat(has("PAIR", "A = 3 AND B = 1")).isFalse();
    }

    /**
     * 分块路径同样是「先关自动提交、异常必回滚」:批删循环是另一份代码,不能因为主路径
     * 有测试就默认它也对。
     */
    @Test
    void aChunkedComparisonRollsBackWhenTheCommitFails() throws SQLException {
        exec(source, "CREATE TABLE BIG_T (ID BIGINT PRIMARY KEY)");
        exec(target, "CREATE TABLE BIG_T (ID BIGINT PRIMARY KEY)");
        insertRange(source, "BIG_T", 1, 1);
        insertRange(target, "BIG_T", 1, 60);

        AtomicInteger rollbacks = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        Connection failingCommit = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("commit".equals(method.getName()) && commits.getAndIncrement() == 0) {
                        throw new IllegalStateException("driver blew up mid-delete");
                    }
                    if ("rollback".equals(method.getName())) {
                        rollbacks.incrementAndGet();
                    }
                    return method.invoke(target, args);
                });

        assertThatThrownBy(() -> service(chunkedProperties()).syncDeletions(source,
                failingCommit, table("BIG_T", "ID", 25_000L), ctx))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rollbacks).as("the open transaction must be rolled back").hasValue(1);
        assertThat(count("BIG_T")).as("a failed delete batch must leave the rows in place")
                .isEqualTo(60);
    }

    /**
     * 事务只在真要删第一行时才打开。读取阶段的失败发生在自动提交状态下,此时若去回滚,
     * PostgreSQL 会抛 "Cannot rollback when autoCommit is enabled" —— 真实错误被替换成
     * 一条误导性的错误(第一次真实库联调就是这么被掩盖的)。
     */
    @Test
    void aReadFailureIsNotMaskedByARollbackOutsideATransaction() throws SQLException {
        exec(target, "CREATE TABLE BIG_T (ID BIGINT PRIMARY KEY)");
        insertRange(target, "BIG_T", 1, 60);
        // 源表不存在:第一块回源查在不在时会失败,此时还没有开始删除。

        AtomicInteger rollbacks = new AtomicInteger();
        Connection watched = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("rollback".equals(method.getName())) {
                        rollbacks.incrementAndGet();
                    }
                    return method.invoke(target, args);
                });

        assertThatThrownBy(() -> service(chunkedProperties()).syncDeletions(source, watched,
                table("BIG_T", "ID", 25_000L), ctx))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("BIG_T");

        assertThat(rollbacks).as("nothing was deleted yet, so there is nothing to roll back")
                .hasValue(0);
        assertThat(count("BIG_T")).isEqualTo(60);
    }

    /**
     * 分块比对默认关闭:超过内存上限的表仍然和以前一样跳过并说明原因,不让升级后的版本
     * 悄悄开始在百万行表上每轮扫两遍全表。
     */
    @Test
    void theChunkedCeilingIsOffUntilItIsRaised() {
        assertThat(service.deletionDetectionSkipReason(table("BIG", "ID", 25_000L),
                new SyncConfig()))
                .contains("25000")
                .contains("sync.full-compare-max-rows");

        assertThat(service(chunkedProperties()).deletionDetectionSkipReason(
                table("BIG", "ID", 25_000L), new SyncConfig())).isNull();
        assertThat(service(chunkedProperties()).deletionDetectionSkipReason(
                table("BIG", "ID", 6_000_000L), new SyncConfig()))
                .contains("sync.delete-compare-max-rows");
    }

    /**
     * 主键上有列清洗规则时不能分块:目标库里存的是清洗后的键,拿它回源库按等值查不到原始
     * 键(大小写/前后缀都变了),会误判为"源库已删除"并清掉整表。这类表退回内存比对。
     */
    @Test
    void aPrimaryKeyRuleForcesTheInMemoryComparison() throws SQLException {
        exec(source, "CREATE TABLE CODES (CODE VARCHAR(32) PRIMARY KEY)");
        exec(target, "CREATE TABLE CODES (CODE VARCHAR(32) PRIMARY KEY)");
        exec(source, "INSERT INTO CODES VALUES ('tmp_A')");
        exec(target, "INSERT INTO CODES VALUES ('A')");

        ColumnRule strip = new ColumnRule();
        strip.setTable("CODES");
        strip.setColumn("CODE");
        strip.setType(ColumnRuleType.STRIP_PREFIX);
        strip.setArg("tmp_");
        SyncConfig config = new SyncConfig();
        config.setColumnRules(List.of(strip));
        SyncContext cleansed = SyncContext.builder()
                .config(config)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();

        // 清洗后的键无法回查原始键,只能整表比对;整表比对受内存上限约束,所以大表直接跳过。
        assertThat(service(chunkedProperties()).deletionDetectionSkipReason(
                table("CODES", "CODE", 25_000L), config))
                .contains("sync.full-compare-max-rows");

        // 上限以内仍然按清洗后的键正确比对(这里目标 'A' 就是源 'tmp_A' 的清洗结果)。
        int deleted = service(chunkedProperties()).syncDeletions(source, target,
                table("CODES", "CODE", 1L), cleansed);

        assertThat(deleted).isZero();
        assertThat(count("CODES")).isEqualTo(1);
    }

    /**
     * The skip must be explainable, not silent: the engine turns this reason into one
     * user-visible note per table so an operator does not believe deletions propagate when
     * they cannot be detected at all.
     */
    @Test
    void deletionDetectionSaysWhyATableIsSkipped() {
        // Over the cap the two key sets would have to be read whole, so the feature is off.
        assertThat(service.deletionDetectionSkipReason(table("BIG", "ID", 20_001L),
                new SyncConfig()))
                .contains("20001")
                .contains("sync.full-compare-max-rows");
        // At the cap it still runs.
        assertThat(service.deletionDetectionSkipReason(table("BIG", "ID", 20_000L),
                new SyncConfig())).isNull();

        // No estimate is not a blank cheque: without a size the key sets cannot be bounded.
        TableMeta unknown = table("BIG", "ID", 1L);
        unknown.setApproximateRowCount(null);
        assertThat(service.deletionDetectionSkipReason(unknown, new SyncConfig()))
                .contains("could not be estimated");

        // A keyless table has nothing to match rows on in the first place.
        TableMeta keyless = table("BIG", "ID", 1L);
        keyless.setPrimaryKeys(List.of());
        assertThat(service.deletionDetectionSkipReason(keyless, new SyncConfig()))
                .contains("primary key");
    }

    @Test
    void keyPartsAreNormalizedByType() {
        assertThat(DataSyncService.keyPartOf(new byte[] {0x01, (byte) 0xAB})).isEqualTo("01ab");

        // Every numeric flavor lands on the same canonical decimal text.
        assertThat(DataSyncService.keyPartOf(new BigDecimal("1.50")))
                .isEqualTo(DataSyncService.keyPartOf(1.5d))
                .isEqualTo("1.5");
        assertThat(DataSyncService.keyPartOf(42L))
                .isEqualTo(DataSyncService.keyPartOf(new BigDecimal("42")))
                .isEqualTo(DataSyncService.keyPartOf(42));

        // The same instant read as Timestamp by one driver and LocalDateTime by the other.
        Timestamp ts = Timestamp.valueOf("2024-01-01 10:00:00");
        assertThat(DataSyncService.keyPartOf(ts))
                .isEqualTo(DataSyncService.keyPartOf(LocalDateTime.of(2024, 1, 1, 10, 0)));

        assertThat(DataSyncService.keyPartOf(null)).isEmpty();
        assertThat(DataSyncService.keyPartOf("abc")).isEqualTo("abc");
    }

    private TableMeta table(String name, String pk, long approximateRows) {
        return table(name, List.of(pk), approximateRows);
    }

    private TableMeta table(String name, List<String> pk, long approximateRows) {
        TableMeta table = new TableMeta();
        table.setName(name);
        table.setPrimaryKeys(pk);
        // Below the comparison ceiling, so deletion detection actually runs.
        table.setApproximateRowCount(approximateRows);
        return table;
    }

    /** Chunked comparison enabled up to five million rows. */
    private SyncProperties chunkedProperties() {
        SyncProperties properties = new SyncProperties();
        properties.setDeleteCompareMaxRows(5_000_000L);
        return properties;
    }

    private DataSyncService service(SyncProperties properties) {
        return new DataSyncService(properties);
    }

    private void insertRange(Connection conn, String tableName, int from, int to)
            throws SQLException {
        try (java.sql.PreparedStatement ps =
                     conn.prepareStatement("INSERT INTO " + tableName + " VALUES (?)")) {
            for (int i = from; i <= to; i++) {
                ps.setInt(1, i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private boolean has(String tableName, Object key) throws SQLException {
        return has(tableName, "ID = " + key);
    }

    private boolean has(String tableName, String condition) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM " + tableName
                     + " WHERE " + condition)) {
            return rs.next();
        }
    }

    private long count(String tableName) throws SQLException {
        return countRows(tableName);
    }

    private long countRows(String tableName) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
