package com.qqmu.jync.it;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 配置包从 H2 换到真实 PostgreSQL 的往返。
 *
 * <p>与 MySQL 的用例相同：计数、外键重映射、密钥转译、单例与状态表。显式运行：
 * {@code mvn test -Dtest=ConfigMigrationPostgresIT}；没有 Docker 时整类跳过。
 */
@Testcontainers(disabledWithoutDocker = true)
class ConfigMigrationPostgresIT extends AbstractConfigMigrationIT {

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

    @Override
    protected String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    @Override
    protected String driverClass() {
        return POSTGRES.getDriverClassName();
    }

    @Override
    protected String username() {
        return POSTGRES.getUsername();
    }

    @Override
    protected String password() {
        return POSTGRES.getPassword();
    }
}
