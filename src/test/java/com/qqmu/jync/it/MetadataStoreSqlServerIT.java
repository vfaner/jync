package com.qqmu.jync.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 元数据库换成 SQL Server 时，Hibernate 建表与全部元数据契约仍然成立。
 *
 * <p>SQL Server 不在官方元数据库支持矩阵里（部署文档只列 MySQL 8 / PostgreSQL 16 /
 * Oracle 23），所以这里证明的是「JPA 层的 SQL 形状可移植」，不是宣布它受支持：有人把
 * {@code SPRING_DATASOURCE_*} 指到 SQL Server 时，启动建表、自增、{@code @Lob} 映射与
 * 图表聚合都不会因为方言差异炸掉。
 *
 * <p>镜像只有 amd64，在 Apple Silicon 上以模拟方式运行，首次启动要一两分钟。等待日志用
 * {@code Recovery is complete}，不能用 "ready for client connections"：后者先出现时
 * SA 登录仍会被拒，整套测试会以 Login failed 失败。
 *
 * <p>{@code *IT} 不被 surefire 默认包含；本机没有 Docker 时整个类会被跳过。显式运行：
 * {@code mvn test -Dtest=MetadataStoreSqlServerIT}。
 */
@Testcontainers(disabledWithoutDocker = true)
class MetadataStoreSqlServerIT extends AbstractMetadataStoreIT {

    private static final String PASSWORD = "Jync_Probe_2026!";

    @Container
    private static final GenericContainer<?> SQLSERVER =
            new GenericContainer<>(
                    DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
                    .withEnv("ACCEPT_EULA", "Y")
                    .withEnv("MSSQL_SA_PASSWORD", PASSWORD)
                    .withExposedPorts(1433)
                    .waitingFor(Wait.forLogMessage(".*Recovery is complete.*", 1)
                            .withStartupTimeout(Duration.ofSeconds(360)));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        SQLSERVER.start();
        createDatabase();
        registry.add("spring.datasource.url", () -> jdbcUrl("jync_meta"));
        registry.add("spring.datasource.driver-class-name",
                () -> "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    private static String jdbcUrl(String database) {
        return "jdbc:sqlserver://" + SQLSERVER.getHost() + ":" + SQLSERVER.getMappedPort(1433)
                + ";databaseName=" + database + ";encrypt=false";
    }

    private static void createDatabase() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl("master"), "sa", PASSWORD);
             Statement statement = conn.createStatement()) {
            statement.execute("IF DB_ID('jync_meta') IS NULL CREATE DATABASE jync_meta");
        } catch (Exception e) {
            throw new IllegalStateException("无法创建 SQL Server 探针库", e);
        }
    }
}
