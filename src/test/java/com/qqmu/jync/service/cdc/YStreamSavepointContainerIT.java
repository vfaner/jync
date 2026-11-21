package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.cdc.YStreamEvent.Kind;
import com.sics.ystream.YstreamClientBoot;
import com.sics.ystream.conf.StartMode;
import com.sics.ystream.conf.YstreamConfig;
import com.sics.ystream.deserializer.DefaultDeserializer;
import com.sics.ystream.result.YstreamLcrInterface;

/**
 * 真库闭环:YashanDB 的 YStream 服务端真实发出的 savepoint LCR → {@link YstreamLcrAdapter}
 * → {@link YStreamTransactionAssembler}。
 *
 * <p>单测能证明"给定带 SSN 的 savepoint LCR,折叠结果正确",但证明不了服务端真的会发
 * {@code SAVEPOINT} / {@code ROLLBACK TO SAVEPOINT} 这两种 LCR,也证明不了它们的 SSN 与
 * 事务内行事件的先后关系。本测试在一个真实事务里执行
 * {@code INSERT 1 → SAVEPOINT sp → INSERT 2 → ROLLBACK TO sp → INSERT 3 → COMMIT},
 * 从真实流里组装提交事务,断言只有 1 和 3,2 被服务端回滚、绝不能被应用到目标。
 *
 * <p>实测记录(23.4.7.100):服务端在本库版本上<b>不发射</b> savepoint/rollback LCR ——
 * 同一个事务在流里只出现 {@code BEGIN → INSERT 1 → INSERT 3 → COMMIT},被回滚的 2 在
 * 解析 redo 时就被过滤。因此本测试只断言折叠后的提交事务不含 2;若某个版本开始发射
 * savepoint LCR,断言仍然成立,并被记录到日志里。
 *
 * <p>YashanDB 的再现实例不是 Testcontainers 默认能起的(需要 SYS 口令与 YStream 授权),
 * 沿用 {@link com.qqmu.jync.it.KingbasePollingIT} 的 opt-in 约定:
 *
 * <pre>
 * JYNC_YASHAN_IT_URL=jdbc:yasdb://host:1688/yashandb \
 * JYNC_YASHAN_IT_USER=sys JYNC_YASHAN_IT_PASSWORD=... \
 * mvn -q test -Dtest=YStreamSavepointContainerIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "JYNC_YASHAN_IT_URL", matches = ".+")
class YStreamSavepointContainerIT {

    private static final String SCHEMA = "JYNC_SRC";
    private static final String TABLE = "YS_SAVEPOINT_IT";
    private static final String SERVICE = "JYNC_IT_SP";
    /** The stream must deliver the whole transaction; generous for a first run on a busy box. */
    private static final long READ_TIMEOUT_MS = 90_000L;

    @Test
    void rowsRolledBackToASavepointNeverReachTheCommittedTransaction() throws Exception {
        String url = env("JYNC_YASHAN_IT_URL");
        String user = env("JYNC_YASHAN_IT_USER");
        String password = env("JYNC_YASHAN_IT_PASSWORD");
        String database = envOrDefault("JYNC_YASHAN_IT_DATABASE", "yashandb");

        YstreamClientBoot<YstreamLcrInterface> client = null;
        try {
            prepareSource(url, user, password);
            createService(url, user, password);

            // 服务 START 到解析器真正开始供数之间有个空窗;等它进入稳定状态再开客户端,
            // 避免把"服务还没活"误判成"捕获不到这张表"。
            Thread.sleep(seeSettleMillis());

            client = YstreamClientBoot.getClient();
            client.open(YstreamConfig.<YstreamLcrInterface>builder()
                    .setServerName(SERVICE)
                    .setHost(hostOf(url))
                    .setPort(String.valueOf(portOf(url)))
                    .setUser(user)
                    .setPassword(password)
                    .setDatabase(database)
                    // START: 从服务端当前进度开始读;本测试的 DML 在 open 之后才发生。
                    .setStartMode(StartMode.START)
                    .setDeserializer(new DefaultDeserializer())
                    .setPollTimeout(1)
                    .setQueueSize(1000)
                    .build());

            runTransactionWithSavepoint(url, user, password);

            CdcTransactionBuffer committed = assembleUntilCommit(client);
            assertThat(committed).as("no committed transaction reached the stream")
                    .isNotNull();

            CdcTableBinding binding = onlyBinding(committed);
            List<Long> ids = new ArrayList<>();
            for (Map<String, Object> row : committed.upserts().get(binding)) {
                ids.add(number(row.get(idColumn(binding))));
            }
            ids.sort(Long::compare);
            assertThat(ids).as("rows after ROLLBACK TO SAVEPOINT must not be applied")
                    .containsExactly(1L, 3L);
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                    // The service is torn down below either way.
                }
            }
            dropServiceQuietly(url, user, password);
            dropTableQuietly(url, user, password);
        }
    }

    private static void prepareSource(String url, String user, String password) throws SQLException {
        try (Connection conn = connect(url, user, password); Statement st = conn.createStatement()) {
            // 上一次失败运行的残留:先清服务再清表。
            for (String existing : services(conn)) {
                if (existing.equalsIgnoreCase(SERVICE)) {
                    callQuietly(conn, "{call DBMS_YSTREAM_ADM.STOP(?)}", SERVICE);
                    callQuietly(conn, "{call DBMS_YSTREAM_ADM.DROP(?)}", SERVICE);
                }
            }
            try {
                st.execute("DROP TABLE " + SCHEMA + "." + TABLE);
            } catch (SQLException absent) {
                // first run
            }
            st.execute("CREATE TABLE " + SCHEMA + "." + TABLE
                    + " (ID INT PRIMARY KEY, NOTE VARCHAR(50))");
            // 根因(实测):没有 (ALL) COLUMNS 补充日志组的表,YStream 一条行事件都不发 ——
            // 这在 ALL_LOG_GROUPS 里可见,产品本身也只提示不代建(YStreamCdcConsumer
            // .missingSupplementalLogging 给出 ALTER 脚本)。加了组之后同一插入立刻出现
            // 在流里,所以测试必须自己补这一步。
            st.execute("ALTER TABLE " + SCHEMA + "." + TABLE
                    + " ADD SUPPLEMENTAL LOG DATA (ALL) COLUMNS");
            // 新建表后立刻 START 服务,解析器拿不到该表的元数据;先写一行并 CHECKPOINT,
            // 让数据字典落盘(CHECKPOINT 需要 SYS,失败也不影响断言;预热行在服务 START
            // 之前提交,不进本次捕获)。
            st.execute("INSERT INTO " + SCHEMA + "." + TABLE + " VALUES (1000, 'warmup')");
            st.execute("COMMIT");
            try {
                st.execute("ALTER SYSTEM CHECKPOINT");
            } catch (SQLException notPrivileged) {
                // 没有 SYS 权限就跑不了;测试用 SYS 时才会成功。
            }
        }
    }

    private static long seeSettleMillis() {
        String raw = System.getenv("JYNC_YASHAN_IT_SETTLE_MS");
        try {
            return raw == null || raw.isBlank() ? 5_000L : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 5_000L;
        }
    }

    private static void createService(String url, String user, String password) throws SQLException {
        try (Connection conn = connect(url, user, password)) {
            call(conn, "{call DBMS_YSTREAM_ADM.CREATE(?)}", SERVICE);
            call(conn, "{call DBMS_YSTREAM_ADM.ADD_TABLES(?, ?, ?)}",
                    SERVICE, "\"" + SCHEMA + "\".\"" + TABLE + "\"", "");
            call(conn, "{call DBMS_YSTREAM_ADM.START(?)}", SERVICE);
        }
    }

    /** One real transaction: row 2 is rolled back to the savepoint and must never be applied. */
    private static void runTransactionWithSavepoint(String url, String user, String password)
            throws SQLException {
        try (Connection conn = connect(url, user, password); Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);
            st.execute("INSERT INTO " + SCHEMA + "." + TABLE + " VALUES (1, 'before')");
            st.execute("SAVEPOINT sp_it");
            st.execute("INSERT INTO " + SCHEMA + "." + TABLE + " VALUES (2, 'rolled-back')");
            st.execute("ROLLBACK TO SAVEPOINT sp_it");
            st.execute("INSERT INTO " + SCHEMA + "." + TABLE + " VALUES (3, 'after')");
            conn.commit();
        }
    }

    /**
     * The consumer's own state machine, minus the target: BEGIN → DML/savepoint → COMMIT, and
     * the COMMIT hands over the assembler's buffer exactly like {@code handle()} does.
     *
     * <p>Keeps consuming until the transaction carrying {@code id=3} commits — the service may
     * first deliver other transactions (its own start position, the warm-up). Any committed
     * transaction that contains {@code id=2} fails immediately: that is the rolled-back row
     * leaking to the target.
     *
     * <p>Savepoint LCRs are <b>not</b> required: 23.4.7.100 filters the rolled-back DML at
     * parse time and never emits them. When a server version does emit them, the assembler
     * branches run here too (and the observation is printed).
     */
    private static CdcTransactionBuffer assembleUntilCommit(YstreamClientBoot<YstreamLcrInterface> client)
            throws Exception {
        YStreamTransactionAssembler tx = new YStreamTransactionAssembler();
        CdcTableBinding binding = null;
        Set<Kind> seen = new HashSet<>();
        boolean sawSavepoint = false;
        long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            YstreamLcrInterface lcr = client.next();
            if (lcr == null) {
                continue;
            }
            YStreamEvent event = YstreamLcrAdapter.toEvent(lcr);
            if (event == null || event.kind() == null) {
                continue;
            }
            seen.add(event.kind());
            if (event.kind() == Kind.DML) {
                if (event.schema() == null || !event.schema().equalsIgnoreCase(SCHEMA)
                        || !TABLE.equalsIgnoreCase(event.table())) {
                    continue; // another captured table of this service
                }
                binding = bindingFor(event, binding);
                if (event.operation() == YStreamEvent.Operation.DELETE) {
                    tx.delete(binding, event.oldValues());
                } else {
                    tx.upsert(binding, event.newValues());
                }
                continue;
            }
            switch (event.kind()) {
                case XACT_BEGIN -> tx.begin();
                case XACT_SAVEPOINT -> {
                    assertThat(event.savepointSsn()).as("savepoint LCR must carry its SSN")
                            .isNotZero();
                    sawSavepoint = true;
                    tx.savepoint(event.savepointSsn());
                }
                case XACT_ROLLBACK_TO_SAVEPOINT -> {
                    assertThat(event.savepointSsn()).as("rollback LCR must carry its SSN")
                            .isNotZero();
                    sawSavepoint = true;
                    tx.rollbackToSavepoint(event.savepointSsn());
                }
                case XACT_COMMIT -> {
                    CdcTransactionBuffer committed = tx.take();
                    Set<Long> ids = committedIds(committed);
                    assertThat(ids).as("a commit leaked the rolled-back row; stream saw %s", seen)
                            .doesNotContain(2L);
                    if (ids.contains(3L)) {
                        System.out.println("YStream IT: savepoint LCRs observed = "
                                + sawSavepoint);
                        return committed;
                    }
                }
                default -> {
                    // DDL/CHUNK/IGNORED are irrelevant to this test.
                }
            }
        }
        throw new AssertionError(
                "our COMMIT LCR did not arrive in " + READ_TIMEOUT_MS + "ms; saw " + seen);
    }

    private static Set<Long> committedIds(CdcTransactionBuffer buffer) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> entry
                : buffer.upserts().entrySet()) {
            String idColumn = idColumn(entry.getKey());
            for (Map<String, Object> row : entry.getValue()) {
                ids.add(number(row.get(idColumn)));
            }
        }
        return ids;
    }

    /** A binding over the row's own columns; only the primary key matters for folding. */
    private static CdcTableBinding bindingFor(YStreamEvent event, CdcTableBinding current) {
        if (current != null || event.newValues() == null) {
            return current;
        }
        TableMeta meta = new TableMeta();
        meta.setName(event.table());
        for (String column : event.newValues().keySet()) {
            ColumnMeta c = new ColumnMeta();
            c.setName(column);
            c.setJdbcType(Types.VARCHAR);
            meta.getColumns().add(c);
            if ("ID".equalsIgnoreCase(column)) {
                meta.getPrimaryKeys().add(column);
            }
        }
        return CdcTableBinding.of(event.schema(), event.table(), meta,
                event.table(), null);
    }

    private static CdcTableBinding onlyBinding(CdcTransactionBuffer committed) {
        Set<CdcTableBinding> bindings = new LinkedHashSet<>();
        bindings.addAll(committed.upserts().keySet());
        bindings.addAll(committed.deletes().keySet());
        assertThat(bindings).hasSize(1);
        return bindings.iterator().next();
    }

    private static String idColumn(CdcTableBinding binding) {
        return binding.pkNames().get(0);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static void dropServiceQuietly(String url, String user, String password) {
        try (Connection conn = connect(url, user, password)) {
            callQuietly(conn, "{call DBMS_YSTREAM_ADM.STOP(?)}", SERVICE);
            callQuietly(conn, "{call DBMS_YSTREAM_ADM.DROP(?)}", SERVICE);
        } catch (SQLException ignored) {
            // best effort — the server may already be gone
        }
    }

    private static void dropTableQuietly(String url, String user, String password) {
        try (Connection conn = connect(url, user, password); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE " + SCHEMA + "." + TABLE);
        } catch (SQLException ignored) {
            // best effort
        }
    }

    private static List<String> services(Connection conn) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT SERVER_NAME FROM V$YSTREAM_SERVER")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static void call(Connection conn, String sql, String... args) throws SQLException {
        try (CallableStatement cs = conn.prepareCall(sql)) {
            for (int i = 0; i < args.length; i++) {
                cs.setString(i + 1, args[i]);
            }
            cs.execute();
        }
    }

    private static void callQuietly(Connection conn, String sql, String... args) {
        try {
            call(conn, sql, args);
        } catch (SQLException ignored) {
            // A STOP on a CREATED service, or a DROP of one that is gone, is expected.
        }
    }

    private static Connection connect(String url, String user, String password)
            throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static String hostOf(String url) {
        String afterScheme = url.substring(url.indexOf("://") + 3);
        return afterScheme.substring(0, afterScheme.indexOf(':'));
    }

    private static int portOf(String url) {
        String afterHost = url.substring(url.indexOf("://") + 3 + hostOf(url).length() + 1);
        String port = afterHost.substring(0, afterHost.indexOf('/'));
        return Integer.parseInt(port);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        assertThat(value).as("environment variable %s", name).isNotBlank();
        return value;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
