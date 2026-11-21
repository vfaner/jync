package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.SqlServerMetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * 真库验证 SQL Server 的分块扫描:一次窗口里有 N 笔小事务时,{@code max-rows-per-cycle}
 * 必须让消费在**事务边界**停下,并把水位留在最后一个完整事务上,下一轮从那里继续。
 *
 * <p>判据不是"能跑完",而是三件事同时成立:
 * <ul>
 *   <li>第一轮确实被截断(提交行数 &lt; 全部行数,nextLsn &lt; maxLsn);</li>
 *   <li>多轮之后每一行恰好被应用一次(既不丢,也没有被重复提交——重复虽然在目标端幂等,
 *       但每多一次就是一次真实写入);</li>
 *   <li>最后一轮把 nextLsn 推到 maxLsn,窗口被完整消费。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
class SqlServerCdcChunkingContainerIT {

    private static final String PASSWORD = "Jync#2026test";
    private static final String DATABASE = "cdc_chunk";
    private static final String CAPTURE_INSTANCE = "dbo_chunk_orders";
    private static final int TRANSACTIONS = 10;
    /** Small enough that the scan must stop several times. */
    private static final long ROWS_PER_CYCLE = 2L;

    @Container
    private static final GenericContainer<?> MSSQL =
            new GenericContainer<>(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                    .withEnv("ACCEPT_EULA", "Y")
                    .withEnv("MSSQL_SA_PASSWORD", PASSWORD)
                    .withEnv("MSSQL_AGENT_ENABLED", "true")
                    .withEnv("MSSQL_PID", "Developer")
                    .withExposedPorts(1433)
                    .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)));

    @BeforeAll
    static void prepareCdc() throws Exception {
        awaitAgent();
        try (Connection master = connect("master"); Statement st = master.createStatement()) {
            st.execute("IF DB_ID('" + DATABASE + "') IS NULL CREATE DATABASE " + DATABASE);
        }
        try (Connection conn = connect(DATABASE); Statement st = conn.createStatement()) {
            st.execute("EXEC sys.sp_cdc_enable_db");
            st.execute("CREATE TABLE dbo.chunk_orders (id INT PRIMARY KEY, note NVARCHAR(50))");
            enableCdcTableWithAgentRetry(st, "chunk_orders");
            try {
                st.execute("EXEC msdb.dbo.sp_start_job N'cdc." + DATABASE + "_capture'");
            } catch (SQLException alreadyRunning) {
                // fine
            }
        }
    }

    @Test
    void theWindowIsConsumedInTransactionAlignedChunks() throws Exception {
        // One row per transaction: 10 distinct start_lsn values.
        for (int id = 1; id <= TRANSACTIONS; id++) {
            try (Connection conn = connect(DATABASE); Statement st = conn.createStatement()) {
                st.execute("INSERT INTO dbo.chunk_orders VALUES (" + id + ", N'row" + id + "')");
            }
        }
        awaitCapturedRows(TRANSACTIONS);

        List<Integer> applied = new ArrayList<>();
        CdcTargetTransaction target = mock(CdcTargetTransaction.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = invocation.getArgument(1);
            for (Map<String, Object> row : rows) {
                applied.add(((Number) row.get("id")).intValue());
            }
            return null;
        }).when(target).applyUpserts(any(), anyList());
        CdcTargetTransactionFactory factory = mock(CdcTargetTransactionFactory.class);
        when(factory.open(any(), any())).thenReturn(target);

        SqlServerCdcConsumer consumer = consumer(factory);
        try (Connection conn = connect(DATABASE)) {
            String fromLsn;
            String maxLsn;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT CONVERT(varchar(32), sys.fn_cdc_get_min_lsn('"
                                 + CAPTURE_INSTANCE + "'), 1),"
                                 + " CONVERT(varchar(32), sys.fn_cdc_get_max_lsn(), 1)")) {
                assertThat(rs.next()).isTrue();
                fromLsn = rs.getString(1);
                maxLsn = rs.getString(2);
            }

            String cursor = fromLsn;
            SqlServerCdcConsumer.DrainResult first = consumer.drain(conn, cursor);
            assertThat(first.nextLsn).as("the first cycle must stop at its row budget")
                    .isNotNull()
                    .isNotEqualToIgnoringCase(maxLsn);
            assertThat(first.rows).isLessThan(TRANSACTIONS);
            cursor = first.nextLsn;

            int cycles = 1;
            while (cycles < 50 && cursor != null && !cursor.equalsIgnoreCase(maxLsn)) {
                SqlServerCdcConsumer.DrainResult next = consumer.drain(conn, cursor);
                assertThat(next.nextLsn).as("cycle %s must make progress", cycles)
                        .isNotNull();
                cursor = next.nextLsn;
                cycles++;
            }
            assertThat(cycles).as("the whole window must be consumed").isLessThan(50);
            assertThat(cursor).isEqualToIgnoringCase(maxLsn);
        }

        // Every row exactly once: no loss and no double submit across the chunk boundary.
        Set<Integer> unique = new LinkedHashSet<>(applied);
        assertThat(unique).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(1, TRANSACTIONS).boxed().toList());
        assertThat(applied).as("a row must not be applied twice")
                .hasSize(unique.size());
    }

    private static SqlServerCdcConsumer consumer(CdcTargetTransactionFactory factory)
            throws SQLException {
        Project project = new Project();
        project.setId(31L);
        project.setName("mssql-chunk-it");

        DatabaseConfig sourceConfig = new DatabaseConfig();
        sourceConfig.setId(1L);
        sourceConfig.setType(DatabaseType.SQLSERVER);
        sourceConfig.setHost("localhost");
        sourceConfig.setPort(1433);

        SyncConfig config = new SyncConfig();
        SyncContext ctx = SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceConfig)
                .sourceType(DatabaseType.SQLSERVER)
                .sourceSchema("dbo")
                .sourceReader(new SqlServerMetadataReader())
                .build();

        DataSourceManager dataSourceManager = mock(DataSourceManager.class);
        // Binding resolution opens and closes its own connection: hand it a fresh one each
        // call, never the connection the scan is using.
        when(dataSourceManager.getConnection(any()))
                .thenAnswer(invocation -> connect(DATABASE));

        SyncProperties properties = new SyncProperties();
        properties.getCdc().getMssql().setMaxRowsPerCycle(ROWS_PER_CYCLE);
        properties.getLob().setStreamingEnabled(false);

        return new SqlServerCdcConsumer(project, mock(SyncLockService.LockHandle.class),
                ctx, mock(CdcPositionStore.class), mock(CdcBaselineService.class),
                mock(SyncTaskStore.class), mock(SyncStateWriter.class),
                dataSourceManager, new CdcRuntimeStats(), properties, factory);
    }

    private static void awaitCapturedRows(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000L;
        while (true) {
            try (Connection conn = connect(DATABASE); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM cdc.dbo_chunk_orders_CT")) {
                assertThat(rs.next()).isTrue();
                if (rs.getInt(1) >= expected) {
                    return;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("CDC captured fewer than " + expected + " rows");
            }
            Thread.sleep(2_000L);
        }
    }

    private static void awaitAgent() throws Exception {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (true) {
            try (Connection conn = connect("master"); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT status_desc FROM sys.dm_server_services"
                         + " WHERE servicename LIKE 'SQL Server Agent%'")) {
                if (rs.next() && "Running".equalsIgnoreCase(rs.getString(1))) {
                    return;
                }
            } catch (SQLException notReady) {
                // still booting
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

    /**
     * SQL Server Agent starts slowly under amd64 emulation; sp_cdc_enable_table fails with
     * 14258/22836 ("SQLServerAgent is starting") until it accepts job steps. Retry only that
     * condition, for up to 30 seconds.
     */
    private static void enableCdcTableWithAgentRetry(Statement st, String table)
            throws SQLException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                st.execute("EXEC sys.sp_cdc_enable_table @source_schema='dbo',"
                        + " @source_name='" + table + "', @role_name=NULL,"
                        + " @supports_net_changes=1");
                return;
            } catch (SQLException e) {
                String message = String.valueOf(e.getMessage());
                boolean agentStarting = message.contains("14258") || message.contains("22836");
                if (!agentStarting || attempt >= 30) {
                    throw e;
                }
                Thread.sleep(1_000L);
            }
        }
    }
}
