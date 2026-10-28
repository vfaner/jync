package com.qqmu.jync.service.connection;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.cdc.BinlogCdcProbe;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.cdc.LogMinerCdcProbe;
import com.qqmu.jync.service.cdc.PgLogicalReplicationCdcProbe;
import com.qqmu.jync.service.cdc.SqlServerCdcProbe;
import com.zaxxer.hikari.HikariDataSource;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/** Validates that a configured database is reachable and reports what it is. */
@Service
@Slf4j
public class ConnectionTestService {

    private final DataSourceManager dataSourceManager;
    private final BinlogCdcProbe cdcProbe;
    private final LogMinerCdcProbe logMinerProbe;
    private final PgLogicalReplicationCdcProbe pgProbe;
    private final SqlServerCdcProbe mssqlProbe;
    private final TargetDdlProbe targetDdlProbe;
    private final SyncProperties properties;

    public ConnectionTestService(DataSourceManager dataSourceManager, BinlogCdcProbe cdcProbe,
                                 LogMinerCdcProbe logMinerProbe,
                                 PgLogicalReplicationCdcProbe pgProbe,
                                 SqlServerCdcProbe mssqlProbe,
                                 TargetDdlProbe targetDdlProbe,
                                 SyncProperties properties) {
        this.dataSourceManager = dataSourceManager;
        this.cdcProbe = cdcProbe;
        this.logMinerProbe = logMinerProbe;
        this.pgProbe = pgProbe;
        this.mssqlProbe = mssqlProbe;
        this.targetDdlProbe = targetDdlProbe;
        this.properties = properties;
    }

    /**
     * Opens a connection and reads its product banner.
     *
     * @return the outcome, never null; failures are reported rather than thrown so the UI
     *         can show the driver's own message, which is usually the most useful hint
     */
    public TestResult test(DatabaseConfig config) {
        long start = System.currentTimeMillis();
        // Fail with an i18n key before the driver loader can produce an English stacktrace-style
        // message ("not on the classpath and no jar path was supplied").
        if (DriverPresence.externalJarRequired(config.getType(), config.getCustomDriver())
                && !StringUtils.hasText(config.getCustomJarPath())) {
            return TestResult.failure("error.driver.jar.required", 0);
        }
        try {
            if (config.getId() == null) {
                // Test from an unsubmitted form: throwaway pool, closed right here. Going
                // through the cached path would leak one Hikari pool per button click.
                try (HikariDataSource ds = dataSourceManager.createTransientDataSource(config);
                     Connection conn = ds.getConnection()) {
                    return describe(config, conn, start);
                }
            }
            try (Connection conn = dataSourceManager.getConnection(config)) {
                return describe(config, conn, start);
            }
        } catch (SQLException e) {
            log.warn("Connection test failed for '{}': {}", config.getName(), e.getMessage());
            return TestResult.failure(sqlMessage(e), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("Connection test failed for '{}': {}", config.getName(), e.getMessage());
            return TestResult.failure(e.getMessage() == null ? e.toString() : e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    private TestResult describe(DatabaseConfig config, Connection conn, long start) throws SQLException {
        if (!conn.isValid(5)) {
            return TestResult.failure("Connection was established but is not valid",
                    System.currentTimeMillis() - start);
        }
        DatabaseMetaData md = conn.getMetaData();
        String product = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
        String driver = md.getDriverName() + " " + md.getDriverVersion();
        List<CdcProbeResult.Check> cdcChecks = null;
        // CDC 就绪检查是源端专属:目标库永远不会被挖掘日志,对 TARGET 连接跑这些检查
        // (binlog/LogMiner/逻辑复制权限等)只会误导用户。role 在表单为必填,这里对
        // 历史 null 数据按 SOURCE 处理(字段 backfill 之前默认值就是 SOURCE)。
        // 目标侧换成一项自己的预检:目标账号能不能在写入的 schema 里建表。此前这里
        // 什么都不查,配置页一路绿灯,直到建表时才甩出 ORA-01031。
        if (config.getRole() == ConnectionRole.TARGET) {
            try {
                List<CdcProbeResult.Check> targetChecks = targetDdlProbe.probe(conn, config);
                cdcChecks = targetChecks.isEmpty() ? null : targetChecks;
            } catch (RuntimeException e) {
                // 与源端一样:预检只是附加信息,炸掉降级为无检查项(B2)
                log.warn("Target DDL probe failed during connection test; reporting none", e);
            }
        } else if (config.getType() == DatabaseType.MYSQL) {
            try {
                // 连接页没有项目上下文 → plannedServerId 传 0(只查非 0,不查撞 id)
                cdcChecks = cdcProbe.probe(conn, 0L).getChecks();
            } catch (RuntimeException e) {
                // 探测只是附加信息:自身炸掉降级为无检查项,绝不阻断连接测试(B2)
                log.warn("CDC probe failed during connection test; reporting none", e);
            }
        } else if (config.getType() == DatabaseType.ORACLE) {
            try {
                // 连接页无项目/表上下文 → ownerTables 空集(跳过表级补充日志检查);
                // P0-2:config 随行,CDB 挖掘模式检查据此判断挖掘账号是否已配置
                cdcChecks = logMinerProbe.probe(conn, List.of(), config).getChecks();
            } catch (RuntimeException e) {
                log.warn("LogMiner probe failed during connection test; reporting none", e);
            }
        } else if (config.getType() != null
                && config.getType().getFamily() == DatabaseType.DialectFamily.POSTGRES) {
            try {
                // 连接页无项目/表上下文 → ownerTables 空集(跳过 REPLICA IDENTITY 检查)
                cdcChecks = pgProbe.probe(conn, List.of()).getChecks();
            } catch (RuntimeException e) {
                log.warn("PG logical probe failed during connection test; reporting none", e);
            }
        } else if (config.getType() == DatabaseType.SQLSERVER) {
            try {
                cdcChecks = mssqlProbe.probe(conn, List.of()).getChecks();
            } catch (RuntimeException e) {
                log.warn("SQL Server CDC probe failed during connection test; reporting none", e);
            }
        }
        TestResult result = TestResult.success(product, driver, conn.getCatalog(),
                conn.getSchema(), System.currentTimeMillis() - start, cdcChecks);
        log.info("Connection test OK for '{}': {}", config.getName(), product);
        return result;
    }

    private String sqlMessage(SQLException e) {
        StringBuilder sb = new StringBuilder();
        if (e.getSQLState() != null) {
            sb.append("[SQLState ").append(e.getSQLState()).append("] ");
        }
        sb.append(e.getMessage());
        // The root cause usually names the actual network or auth problem.
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().equals(e.getMessage())) {
            sb.append(" (cause: ").append(cause.getMessage()).append(')');
        }
        return sb.toString();
    }

    /** Outcome of a connection test. */
    @Getter
    public static class TestResult {
        private final boolean success;
        private final String message;
        private final String productInfo;
        private final String driverInfo;
        private final String catalog;
        private final String schema;
        private final long elapsedMs;
        /** Itemized CDC probe results; null when not probed (non-MySQL) or the probe blew up. */
        private final List<CdcProbeResult.Check> cdcChecks;

        private TestResult(boolean success, String message, String productInfo, String driverInfo,
                           String catalog, String schema, long elapsedMs,
                           List<CdcProbeResult.Check> cdcChecks) {
            this.success = success;
            this.message = message;
            this.productInfo = productInfo;
            this.driverInfo = driverInfo;
            this.catalog = catalog;
            this.schema = schema;
            this.elapsedMs = elapsedMs;
            this.cdcChecks = cdcChecks;
        }

        static TestResult success(String product, String driver, String catalog, String schema, long ms) {
            return success(product, driver, catalog, schema, ms, null);
        }

        static TestResult success(String product, String driver, String catalog, String schema, long ms,
                                  List<CdcProbeResult.Check> cdcChecks) {
            return new TestResult(true, "OK", product, driver, catalog, schema, ms, cdcChecks);
        }

        static TestResult failure(String message, long ms) {
            return new TestResult(false, message, null, null, null, null, ms, null);
        }
    }
}
