package com.qqmu.jync.it;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 元数据库换成真实 MariaDB 时的启动与读写契约。
 *
 * <p>MariaDB 是支持矩阵里第二个 MySQL 协议产品，也是没有 {@code date_trunc} 的那个。
 * 图表聚合曾经是原生 {@code date_trunc} 查询：MySQL / MariaDB 上直接 500
 * （{@code FUNCTION date_trunc does not exist}）。这里把整条链路钉在真实容器上，
 * 包括 {@link AbstractMetadataStoreIT} 里那条按小时 / 分钟分桶的图表契约。
 *
 * <p>{@code *IT} 不被 surefire 默认包含；本机没有 Docker 时整个类会被跳过。显式运行：
 * {@code mvn test -Dtest=MetadataStoreMariaDbIT}。
 */
@Testcontainers(disabledWithoutDocker = true)
class MetadataStoreMariaDbIT extends AbstractMetadataStoreIT {

    @Container
    private static final MariaDBContainer<?> MARIADB =
            new MariaDBContainer<>(DockerImageName.parse("mariadb:11.4"))
                    .withDatabaseName("jync_meta")
                    .withUsername("jync")
                    .withPassword("jync");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // @Container 之后再 start() 是幂等的；显式调用保证属性求值时容器已就绪。
        MARIADB.start();
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
    }
}
