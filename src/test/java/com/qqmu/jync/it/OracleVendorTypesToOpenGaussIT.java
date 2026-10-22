package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Locale;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
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
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.metadata.OracleMetadataReader;
import com.qqmu.jync.service.monitor.CursorStrategy;
import com.qqmu.jync.service.sync.DataSyncService;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * Oracle 专属类型 → openGauss(信创目标)全量: {@code BINARY_FLOAT}, {@code BINARY_DOUBLE},
 * {@code XMLTYPE}, {@code INTERVAL YEAR TO MONTH}, {@code ROWID}。
 *
 * <p>真实驱动证明两件事:源端值被读成可移植的 Java 类型({@code Float}/{@code Double}/
 * 文本),而不是 {@code oracle.sql.*} 私有类;目标列由 {@code VendorTypeMap} 归一化成
 * openGauss 能建的类型(REAL/DOUBLE PRECISION/TEXT/VARCHAR),不再把 {@code XMLTYPE}
 * 之类的名字原样带过去。
 *
 * <p>DB2 无本地镜像,其 {@code DECFLOAT/XML/ROWID} 归一化只有单元测试覆盖
 * ({@code VendorTypeMapTest});本类不涉及。
 *
 * <p>Named {@code *IT} so surefire's default includes skip it; run with
 * {@code mvn test -Dtest=OracleVendorTypesToOpenGaussIT}. Disables itself without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class OracleVendorTypesToOpenGaussIT {

    @Container
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    private static final String SRC = "OG_VENDOR_SRC";

    private static String schema;

    @BeforeAll
    static void prepare() throws SQLException {
        schema = ORACLE.getUsername().toUpperCase(Locale.ROOT);
        try (Connection conn = oracle(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + SRC + " (ID NUMBER(10) PRIMARY KEY,"
                    + " BF BINARY_FLOAT, BD BINARY_DOUBLE, DOC XMLTYPE,"
                    + " IVL INTERVAL YEAR TO MONTH, RID ROWID)");
            st.execute("INSERT INTO " + SRC + " (ID, BF, BD, DOC, IVL, RID)"
                    + " SELECT 1, 1.5, 2.25, XMLTYPE('<a k=\"1\">中</a>'),"
                    + " INTERVAL '1-2' YEAR TO MONTH, ROWID FROM dual");
            st.execute("COMMIT");
        }
    }

    @Test
    void oracleVendorTypesLandInPortableOpenGaussColumns() throws SQLException {
        try (Connection tgt = OpenGaussSupport.jdbc(); Statement st = tgt.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + SRC + "\"");
            st.execute("CREATE TABLE \"" + SRC + "\" (\"ID\" INTEGER PRIMARY KEY,"
                    + " \"BF\" REAL, \"BD\" DOUBLE PRECISION, \"DOC\" TEXT,"
                    + " \"IVL\" VARCHAR(64), \"RID\" VARCHAR(40))");
        }

        DataSyncService.TableSyncResult result;
        try (Connection sourceConn = oracle(); Connection targetConn = OpenGaussSupport.jdbc()) {
            TableMeta meta = new OracleMetadataReader().readTable(sourceConn, schema, SRC);
            result = service().syncTable(sourceConn, targetConn, meta,
                    CursorStrategy.identity("ID", Types.NUMERIC, "numeric primary key"),
                    new SyncProgress(), context());
        }
        assertThat(result.isSuccess()).as(result.getError()).isTrue();

        try (Connection tgt = OpenGaussSupport.jdbc();
             Statement st = tgt.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"BF\"::text AS bf, \"BD\"::text AS bd,"
                     + " \"DOC\" AS doc, \"IVL\" AS ivl, \"RID\" AS rid FROM \"" + SRC + "\"")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("bf")).isEqualTo("1.5");
            assertThat(rs.getString("bd")).isEqualTo("2.25");
            assertThat(rs.getString("doc")).contains("中");
            assertThat(rs.getString("ivl")).isEqualTo("1-2");
            assertThat(rs.getString("rid")).hasSize(18).startsWith("AA");
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
        sourceCfg.setId(31L);
        sourceCfg.setType(DatabaseType.ORACLE);
        DatabaseConfig targetCfg = new DatabaseConfig();
        targetCfg.setId(32L);
        targetCfg.setType(DatabaseType.OPENGAUSS);

        Project project = new Project();
        project.setId(4L);
        project.setName("oracle-vendor-og-it");

        return SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.ORACLE)
                .targetType(DatabaseType.OPENGAUSS)
                .sourceSchema(schema)
                .targetSchema("public")
                .sourceDialect(new OracleDialect())
                .targetDialect(new OpenGaussDialect())
                .batchSize(100)
                .fullLoadBatchSize(100)
                .fullLoadFetchSize(100)
                .fullLoadParallelism(1)
                .connectionProvider(cfg -> cfg.getId() == 31L
                        ? oracle() : OpenGaussSupport.jdbc())
                .build();
    }

    private static Connection oracle() throws SQLException {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(),
                ORACLE.getUsername(), ORACLE.getPassword());
    }
}
