package com.qqmu.jync.it;

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
 * <p>{@code *IT} 不被 surefire 默认包含；本机没有 Docker 时整个类会被跳过。显式运行：
 * {@code mvn test -Dtest=MetadataStoreMysqlIT}。
 */
@Testcontainers(disabledWithoutDocker = true)
class MetadataStoreMysqlIT extends AbstractMetadataStoreIT {

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("jync_meta")
                    .withUsername("jync")
                    .withPassword("jync");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // Connector/J 8 对 MySQL 8 的 caching_sha2_password 在明文连接上需要显式允许取公钥。
        registry.add("spring.datasource.url", () -> withParameters(MYSQL.getJdbcUrl()));
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static String withParameters(String url) {
        return url + (url.contains("?") ? "&" : "?")
                + "allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
    }
}
