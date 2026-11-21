package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.metadata.SqlServerMetadataReader;

/**
 * 真库闭环:SQL Server 2022 + SQL Server Agent 的实际捕获流 → {@code changesSqlInclusive}
 * → {@link SqlServerCdcConsumer.RowReader} → {@link SqlServerTxFold}。
 *
 * <p>单测(如 {@link SqlServerTxFoldTest})喂的是手工构造的行,证明不了两件只在真库里才
 * 会出现的事:
 * <ul>
 *   <li>捕获函数以什么顺序吐出 op=3/op=4 —— 单测两种顺序都覆盖了,真库实际是逐行
 *       交错(3,4,3,4),FIFO 配对在两种顺序下都必须给出同一净效果;</li>
 *   <li>改主键的 UPDATE 真库里被记成什么 —— 实测是 op=1(旧键) + op=2(新键),折叠
 *       后必须变成「删旧键 + 插新键」,而不是幽灵行;</li>
 *   <li>keys-only 读取器对 op=1/op=3 只取主键列,而 op=2/op=4 取整行 —— 真 CT 的
 *       列集和 mssql-jdbc 的 getObject 返回值才是最终判据。</li>
 * </ul>
 *
 * <p>不启用 {@code @SpringBootTest}:这里验证的是消费者内被改动的纯逻辑,不需要完整
 * 上下文(也就不会踩到仓库根 data/tls 的遗留密钥)。
 */
@Testcontainers(disabledWithoutDocker = true)
class SqlServerCdcFoldContainerIT {

    private static final String PASSWORD = "Jync#2026test";
    private static final String DATABASE = "cdc_fold";
    private static final String CAPTURE_INSTANCE = "dbo_orders";

    @Container
    private static final GenericContainer<?> MSSQL =
            new GenericContainer<>(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                    .withEnv("ACCEPT_EULA", "Y")
                    .withEnv("MSSQL_SA_PASSWORD", PASSWORD)
                    // 捕获作业归 SQL Server Agent 管;不开 Agent,变更永远进不了 CT。
                    .withEnv("MSSQL_AGENT_ENABLED", "true")
                    .withEnv("MSSQL_PID", "Developer")
                    .withExposedPorts(1433)
                    .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)));

    @BeforeAll
    static void prepareCdc() throws SQLException, InterruptedException {
        awaitAgentRunning();
        try (Connection master = connect("master"); Statement st = master.createStatement()) {
            st.execute("IF DB_ID('" + DATABASE + "') IS NULL CREATE DATABASE " + DATABASE);
        }
        try (Connection conn = connect(DATABASE); Statement st = conn.createStatement()) {
            st.execute("EXEC sys.sp_cdc_enable_db");
            st.execute("CREATE TABLE dbo.orders (id INT PRIMARY KEY, note NVARCHAR(50))");
            st.execute("EXEC sys.sp_cdc_enable_table @source_schema='dbo',"
                    + " @source_name='orders', @role_name=NULL, @supports_net_changes=1");
            // 建实例时 Agent 可能还没来得及收到通知;显式启动一次,"已在运行"的报错忽略。
            try {
                st.execute("EXEC msdb.dbo.sp_start_job N'cdc." + DATABASE + "_capture'");
            } catch (SQLException alreadyRunningOrNotYetRegistered) {
                // 捕获轮询会给出真正的失败信号。
            }
        }
    }

    @Test
    void realCaptureStreamFoldsToTheSourceNetEffect() throws Exception {
        // CT 行数 = 每次 UPDATE 两行(op=3+4 或 op=1+2),其余一改一行。
        int expectedChangeRows = 3 + 4 + 4 + 1 + 2;
        // phase 1: 三条 INSERT,一条语句一个事务。
        exec("INSERT INTO dbo.orders VALUES (1,N'a'),(2,N'b'),(3,N'c')");
        // phase 2: 集合式 UPDATE 非主键列 —— 真库逐行吐出 op=3/op=4 配对。
        exec("UPDATE dbo.orders SET note = note + N'-u' WHERE id IN (1,2)");
        // phase 3: 集合式 UPDATE 改主键 —— 真库记成 op=1(旧键)+ op=2(新键)。
        exec("UPDATE dbo.orders SET id = id + 10 WHERE id IN (1,2)");
        exec("DELETE FROM dbo.orders WHERE id = 3");
        // phase 5: 同一显式事务内 DELETE 后重新 INSERT 同一主键 —— 净效果是行存在。
        deleteThenReinsertSameKeyInOneTransaction(11);
        awaitCapturedChangeRows(expectedChangeRows);

        CdcTableBinding binding = binding();
        SqlServerTxFold fold = drainIntoFold(binding);

        List<List<Integer>> upsertsByTx = new ArrayList<>();
        List<List<Integer>> deletesByTx = new ArrayList<>();
        List<Map<String, Object>> finalUpserts = new ArrayList<>();
        for (Map<CdcTableBinding, SqlServerTxFold.Buffer> byBinding : fold.ordered().values()) {
            SqlServerTxFold.Buffer buffer = byBinding.get(binding);
            if (buffer == null) {
                continue;
            }
            upsertsByTx.add(ids(buffer.upserts()));
            deletesByTx.add(ids(buffer.deletes()));
            finalUpserts.addAll(buffer.upserts());
        }

        // 五个自动提交/显式事务,按 txLsn 升序落成五组净效果:
        //   insert(1,2,3) → update(1,2) → 改主键(1,2 → 11,12) → delete(3) → 同键删除重插(11)
        assertThat(upsertsByTx).containsExactly(
                List.of(1, 2, 3),
                List.of(1, 2),
                List.of(11, 12),
                List.of(),
                List.of(11));
        assertThat(deletesByTx).containsExactly(
                List.of(),
                List.of(),
                List.of(1, 2),
                List.of(3),
                List.of());

        // op=3 的 before 镜像绝不能覆盖 op=4 的 after 镜像:目标端拿到的是更新后的备注。
        Map<Integer, Object> notes = new LinkedHashMap<>();
        for (Map<String, Object> row : finalUpserts) {
            notes.put(((Number) row.get("id")).intValue(), row.get("note"));
        }
        assertThat(notes).containsEntry(1, "a-u").containsEntry(2, "b-u");
    }

    private static CdcTableBinding binding() throws SQLException {
        try (Connection conn = connect(DATABASE)) {
            TableMeta meta = new SqlServerMetadataReader().readTable(conn, "dbo", "orders");
            assertThat(meta.getPrimaryKeys()).containsExactly("id");
            return CdcTableBinding.of("dbo", "orders", meta, "orders", null);
        }
    }

    /**
     * 用消费者真实的读取链路(同一个 SQL、同一个 RowReader、同一个 fold)把整段捕获流
     * 折出来,而不是另写一套读取代码。
     */
    private static SqlServerTxFold drainIntoFold(CdcTableBinding binding) throws SQLException {
        String minLsn;
        String maxLsn;
        try (Connection conn = connect(DATABASE); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT CONVERT(varchar(32), sys.fn_cdc_get_min_lsn('"
                             + CAPTURE_INSTANCE + "'), 1),"
                             + " CONVERT(varchar(32), sys.fn_cdc_get_max_lsn(), 1)")) {
            assertThat(rs.next()).isTrue();
            minLsn = rs.getString(1);
            maxLsn = rs.getString(2);
        }

        SqlServerTxFold fold = new SqlServerTxFold();
        Set<String> sawOps = new HashSet<>();
        try (Connection conn = connect(DATABASE);
             PreparedStatement ps = conn.prepareStatement(
                     SqlServerCdcConsumer.changesSqlInclusive(CAPTURE_INSTANCE))) {
            ps.setString(1, minLsn);
            ps.setString(2, maxLsn);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> availableCols = availableColumns(rs);
                assertThat(SqlServerCdcConsumer.missingBindingColumns(binding, availableCols))
                        .isEmpty();
                SqlServerCdcConsumer.RowReader full =
                        SqlServerCdcConsumer.RowReader.of(rs, binding, availableCols);
                SqlServerCdcConsumer.RowReader keysOnly =
                        SqlServerCdcConsumer.RowReader.of(rs, binding, availableCols,
                                new HashSet<>(binding.pkNames()));
                while (rs.next()) {
                    String startLsn = rs.getString(1);
                    int op = rs.getInt(2);
                    Map<String, Object> row = SqlServerCdcConsumer.readerFor(op, full, keysOnly)
                            .read(rs);
                    if (op == 1 || op == 3) {
                        // before/delete 镜像只取主键:大 LOB 的删除不应被整行读出。
                        assertThat(row.keySet()).containsExactlyElementsOf(binding.pkNames());
                    } else {
                        assertThat(row.keySet())
                                .containsExactlyInAnyOrderElementsOf(binding.columnNames());
                    }
                    sawOps.add(String.valueOf(op));
                    fold.accept(startLsn, binding, op, row);
                }
            }
        }
        assertThat(sawOps).containsExactlyInAnyOrder("1", "2", "3", "4");
        return fold;
    }

    private static Set<String> availableColumns(ResultSet rs) throws SQLException {
        Set<String> out = new HashSet<>();
        int n = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= n; i++) {
            String name = rs.getMetaData().getColumnName(i);
            if (name != null) {
                out.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** 同一事务里 DELETE(id) 再 INSERT(id):真库 CT 里是 op=1 后接 op=2。 */
    private static int deleteThenReinsertSameKeyInOneTransaction(int id) throws SQLException {
        try (Connection conn = connect(DATABASE); Statement st = conn.createStatement()) {
            conn.setAutoCommit(false);
            st.execute("DELETE FROM dbo.orders WHERE id = " + id);
            st.execute("INSERT INTO dbo.orders VALUES (" + id + ", N'back')");
            conn.commit();
        }
        return 2;
    }

    private static List<Integer> ids(List<Map<String, Object>> rows) {
        List<Integer> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(((Number) row.get("id")).intValue());
        }
        out.sort(Integer::compareTo);
        return out;
    }

    private static int exec(String sql) throws SQLException {
        try (Connection conn = connect(DATABASE); Statement st = conn.createStatement()) {
            return st.executeUpdate(sql);
        }
    }

    private static void awaitCapturedChangeRows(int expected) throws InterruptedException, SQLException {
        long deadline = System.currentTimeMillis() + 120_000L;
        while (true) {
            try (Connection conn = connect(DATABASE); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cdc.dbo_orders_CT")) {
                assertThat(rs.next()).isTrue();
                if (rs.getInt(1) >= expected) {
                    return;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                        "CDC capture job did not record " + expected + " rows in 120s");
            }
            Thread.sleep(2_000L);
        }
    }

    private static void awaitAgentRunning() throws SQLException, InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (true) {
            try (Connection conn = connect("master"); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT status_desc FROM sys.dm_server_services"
                         + " WHERE servicename LIKE 'SQL Server Agent%'")) {
                if (rs.next() && "Running".equalsIgnoreCase(rs.getString(1))) {
                    return;
                }
            } catch (SQLException notReadyYet) {
                // 容器还在初始化 —— 继续等。
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("SQL Server Agent did not start in 180s");
            }
            Thread.sleep(2_000L);
        }
    }

    private static Connection connect(String database) throws SQLException {
        String url = "jdbc:sqlserver://" + MSSQL.getHost() + ":" + MSSQL.getMappedPort(1433)
                + ";databaseName=" + database + ";encrypt=false;trustServerCertificate=true";
        return DriverManager.getConnection(url, "sa", PASSWORD);
    }
}
