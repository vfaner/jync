package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 元数据库换成真实 MySQL 时的启动与读写契约。
 *
 * <p>这一层以前只跑过 H2（{@code MODE=MySQL}），没有在真正的 MySQL 上验证过 Hibernate 的
 * 建表、自增和 {@code @Lob} 映射。容器里的库是全新的，等于模拟一台新装服务器。
 *
 * <p>容器起来之后先手工建一张「旧版本留下的」{@code project} 表：
 * {@code sync_config TINYTEXT}（255 字节）。旧版本把 {@code @Lob} + {@code @Column}（未写
 * length）交给 MySQL 方言按容量挑类型，最小的就是 {@code TINYTEXT}，于是顾客第一次导入
 * 配置包时写入真实同步配置就报 error 1406「Data too long for column 'sync_config'」。
 * Hibernate 的 {@code update} 不会改已有列的类型，所以这段窄列会一直留到启动修复
 * {@code MetadataTextColumnWidener} 把它改成 {@code LONGTEXT}；本类的往返与列类型断言
 * 就是在证明这次修复真的发生。
 *
 * <p>{@code *IT} 不被 surefire 默认包含；本机没有 Docker 时整个类会被跳过。显式运行：
 * {@code mvn test -Dtest=MetadataStoreMysqlIT}。
 */
@Testcontainers(disabledWithoutDocker = true)
class MetadataStoreMysqlIT extends AbstractMetadataStoreIT {

    /** 旧版本建表时 sync_config 的实际类型；见类注释。 */
    private static final String LEGACY_PROJECT_DDL =
            "CREATE TABLE project (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
            + "sync_config TINYTEXT NULL)";

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("jync_meta")
                    .withUsername("jync")
                    .withPassword("jync");

    @Autowired
    private DataSource mysqlDataSource;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // 容器必须在建「旧表」之前就绪；@Container 之后再 start() 是幂等的。
        MYSQL.start();
        createLegacyProjectTable();
        // Connector/J 8 对 MySQL 8 的 caching_sha2_password 在明文连接上需要显式允许取公钥。
        registry.add("spring.datasource.url", () -> withParameters(MYSQL.getJdbcUrl()));
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static void createLegacyProjectTable() {
        try (Connection conn = DriverManager.getConnection(
                withParameters(MYSQL.getJdbcUrl()), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = conn.createStatement()) {
            statement.execute(LEGACY_PROJECT_DDL);
        } catch (SQLException e) {
            throw new IllegalStateException("无法预置旧版本的 project 表", e);
        }
    }

    private static String withParameters(String url) {
        return url + (url.contains("?") ? "&" : "?")
                + "allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
    }

    /** 启动修复必须把旧版本留下的窄列改宽，否则 80KB 的同步配置仍然写不进去。 */
    @Test
    void theStartupRepairWidenedTheColumnTheOldReleaseCreatedTooNarrow() throws Exception {
        assertThat(columnType("project", "sync_config"))
                .as("旧版本建出的 sync_config 必须被启动修复改成 longtext")
                .isEqualTo("longtext");
    }

    /**
     * 一张都不许留 {@code TINYTEXT}：新表由映射里的 {@code length = Integer.MAX_VALUE}
     * 直接建成长文本，旧表由启动修复改宽。
     */
    @Test
    void noMetadataTextColumnIsLeftAtTinyText() throws Exception {
        StringBuilder narrow = new StringBuilder();
        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND DATA_TYPE = 'tinytext' "
                     + "ORDER BY TABLE_NAME, COLUMN_NAME");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                narrow.append(rs.getString(1)).append('.').append(rs.getString(2)).append(' ');
            }
        }
        assertThat(narrow.toString().trim())
                .as("tinytext 装不下一份真实同步配置，任何元数据列都不该停在 255 字节")
                .isEmpty();
    }

    private String columnType(String table, String column) throws SQLException {
        try (Connection conn = mysqlDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("列 %s.%s 必须存在", table, column).isTrue();
                return rs.getString(1);
            }
        }
    }
}
