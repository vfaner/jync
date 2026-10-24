package com.qqmu.jync.it;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 元数据库换成真实 Oracle 时的启动与读写契约。
 *
 * <p>Oracle 是“Oracle 兼容族”（达梦、崖山等）的替身，它比 MySQL 严格得多：标识符大小写、
 * 保留字、CLOB 绑定都会在建表或首次读写时暴露。选 23 Free 而不是 XE 21 与既有 IT 的理由
 * 相同——XE 没有 arm64 构建。
 *
 * <p>显式运行：{@code mvn test -Dtest=MetadataStoreOracleIT}。
 */
@Testcontainers(disabledWithoutDocker = true)
class MetadataStoreOracleIT extends AbstractMetadataStoreIT {

    @Container
    private static final OracleContainer ORACLE =
            new OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23"))
                    .withStartupTimeoutSeconds(600);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.driver-class-name", ORACLE::getDriverClassName);
        registry.add("spring.datasource.username", ORACLE::getUsername);
        registry.add("spring.datasource.password", ORACLE::getPassword);
    }
}
