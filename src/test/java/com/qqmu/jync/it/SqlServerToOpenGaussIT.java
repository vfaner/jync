package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncProgress;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.service.converter.OpenGaussDialect;
import com.qqmu.jync.service.converter.SqlServerDialect;
import com.qqmu.jync.service.metadata.SqlServerMetadataReader;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * SQL Server 专属类型 → openGauss(信创目标)全量: {@code UNIQUEIDENTIFIER}, {@code MONEY},
 * {@code DATETIMEOFFSET}, {@code XML}, {@code GEOMETRY}。
 *
 * <p>驱动的 {@code getObject} 对这些列返回 {@code microsoft.sql.*} 私有类(UUID → 字符串、
 * DateTimeOffset → OffsetDateTime、geometry → 序列化字节),目标驱动一个都不认识;
 * 归一化后落成 VARCHAR/NUMERIC/TIMESTAMPTZ/TEXT/BYTEA。
 *
 * <p>SQL Server 镜像较慢,启动超时给到 300s;无 Docker 时整类跳过。
 * 运行:{@code mvn test -Dtest=SqlServerToOpenGaussIT -Dapi.version=1.44}。
 */
@Testcontainers(disabledWithoutDocker = true)
class SqlServerToOpenGaussIT {

    private static final String PASSWORD = "Jync#test123";

    @Container
    private static final GenericContainer<?> MSSQL =
            new GenericContainer<>(DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                    .withEnv("ACCEPT_EULA", "Y")
                    .withEnv("MSSQL_SA_PASSWORD", PASSWORD)
                    .withExposedPorts(1433)
                    .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)));

    private static final String SRC = "og_vendor";

    @BeforeAll
    static void prepare() throws SQLException {
        awaitReady();
        try (Connection conn = mssql(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + SRC + " (id INT PRIMARY KEY,"
                    + " uid UNIQUEIDENTIFIER, price MONEY, at DATETIMEOFFSET,"
                    + " payload XML, shape GEOMETRY)");
            st.execute("INSERT INTO " + SRC + " VALUES (1,"
                    + " '0F8FAD5B-D9CB-469F-A165-70867728950E', 12.34,"
                    + " '2026-10-22T08:30:00+08:00', N'<a k=\"1\">中</a>',"
                    + " geometry::Point(1, 2, 0))");
        }
    }

    @Test
    void sqlServerVendorTypesLandInPortableOpenGaussColumns() throws SQLException {
        // SQL Server 未加引号的标识符保留小写拼写,元数据里就是小写;
        // openGauss 的引号标识符区分大小写,目标表必须同名小写。
        String target = SRC;
        try (Connection tgt = OpenGaussSupport.jdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + target + "\"");
            st.execute("CREATE TABLE \"" + target + "\" (\"id\" INTEGER PRIMARY KEY,"
                    + " \"uid\" VARCHAR(36), \"price\" NUMERIC(19,4),"
                    + " \"at\" TIMESTAMP WITH TIME ZONE, \"payload\" TEXT,"
                    + " \"shape\" BYTEA)");
        }

        DataSyncService.TableSyncResult result;
        try (Connection sourceConn = mssql(); Connection targetConn = OpenGaussSupport.jdbc()) {
            TableMeta meta = new SqlServerMetadataReader().readTable(sourceConn, "dbo", SRC);
            result = service().syncTable(sourceConn, targetConn, meta,
                    CursorStrategy.identity("id", Types.INTEGER, "integer primary key"),
                    new SyncProgress(), context());
        }
        assertThat(result.isSuccess()).as(result.getError()).isTrue();

        try (Connection tgt = OpenGaussSupport.jdbc();
             Statement st = tgt.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"uid\" AS uid, \"price\"::text AS price,"
                     + " \"at\"::text AS at, \"payload\" AS payload,"
                     + " octet_length(\"shape\") AS shape_len FROM \"" + target + "\"")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("uid")).isEqualToIgnoringCase(
                    "0F8FAD5B-D9CB-469F-A165-70867728950E");
            assertThat(rs.getString("price")).isEqualTo("12.3400");
            assertThat(rs.getString("at")).contains("2026-10-22 08:30:00");
            assertThat(rs.getString("payload")).contains("中");
            assertThat(rs.getInt("shape_len")).isPositive();
        }
    }

    private static DataSyncService service() {
        SyncProperties properties = new SyncProperties();
        properties.setChunkSize(100);
        properties.setFullLoadParallelism(1);
        return new DataSyncService(properties, mock(ChunkCheckpointRepository.class));
    }

    private static SyncContext context() {
        DatabaseConfig sourceCfg = new DatabaseConfig();
        sourceCfg.setId(41L);
        sourceCfg.setType(DatabaseType.SQLSERVER);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(42L);
        targetCfg.setType(DatabaseType.OPENGAUSS);

        Project project = new Project();
        project.setId(5L);
        project.setName("mssql-vendor-og-it");

        return SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.SQLSERVER)
                .targetType(DatabaseType.OPENGAUSS)
                .sourceSchema("dbo")
                .targetSchema("public")
                .sourceDialect(new SqlServerDialect())
                .targetDialect(new OpenGaussDialect())
                .batchSize(100)
                .fullLoadBatchSize(100)
                .fullLoadFetchSize(100)
                .fullLoadParallelism(1)
                .connectionProvider(cfg -> cfg.getId() == 41L
                        ? mssql() : OpenGaussSupport.jdbc())
                .build();
    }

    /**
     * The TCP port and the readiness log line can both appear while the instance still
     * refuses logins (first-boot system database setup), so acceptance is a real login.
     */
    private static void awaitReady() {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (true) {
            try (Connection ignored = mssql()) {
                return;
            } catch (SQLException notReady) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("SQL Server did not accept logins in 180s",
                            notReady);
                }
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted waiting for SQL Server", ie);
                }
            }
        }
    }

    private static Connection mssql() throws SQLException {
        String url = "jdbc:sqlserver://" + MSSQL.getHost() + ":" + MSSQL.getMappedPort(1433)
                + ";databaseName=master;encrypt=false;trustServerCertificate=true";
        return DriverManager.getConnection(url, "sa", PASSWORD);
    }
}
