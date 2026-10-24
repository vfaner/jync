package com.qqmu.jync.it;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 元数据库换成真实 PostgreSQL 时的启动与读写契约。
 *
 * <p>PostgreSQL 本身不在“替代 H2”的常见候选里，但它是 PostgreSQL 兼容族信创库的替身：
 * Kingbase、openGauss、HighGo、Vastbase 走的是同一个 Hibernate 方言。这里跑通，说明换成那类
 * 库时，剩下的未知只剩下驱动差异，而不是实体映射或 DDL。
 *
 * <p>显式运行：{@code mvn test -Dtest=MetadataStorePostgresIT}。
 */
@Testcontainers(disabledWithoutDocker = true)
class MetadataStorePostgresIT extends AbstractMetadataStoreIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                    .withDatabaseName("jync_meta")
                    .withUsername("jync")
                    .withPassword("jync");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
