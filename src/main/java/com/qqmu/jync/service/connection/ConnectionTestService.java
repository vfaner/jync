package com.qqmu.jync.service.connection;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.cdc.BinlogCdcProbe;
import com.qqmu.jync.service.cdc.CdcProbeResult;
import com.qqmu.jync.service.cdc.LogMinerCdcProbe;
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
    private final SyncProperties properties;

    public ConnectionTestService(DataSourceManager dataSourceManager, BinlogCdcProbe cdcProbe,
                                 LogMinerCdcProbe logMinerProbe,
                                 SyncProperties properties) {
        this.dataSourceManager = dataSourceManager;
        this.cdcProbe = cdcProbe;
        this.logMinerProbe = logMinerProbe;
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
        if (config.getType() == DatabaseType.MYSQL) {
            try {
                // 连接页没有项目上下文 → plannedServerId 传 0(只查非 0,不查撞 id)
                cdcChecks = cdcProbe.probe(conn, 0L).getChecks();
            } catch (RuntimeException e) {
                // 探测只是附加信息:自身炸掉降级为无检查项,绝不阻断连接测试(B2)
                log.warn("CDC probe failed during connection test; reporting none", e);
            }
        } else if (config.getType() == DatabaseType.ORACLE) {
            try {
                // 连接页无项目/表上下文 → ownerTables 空集(跳过表级补充日志检查)
                cdcChecks = logMinerProbe.probe(conn, List.of()).getChecks();
            } catch (RuntimeException e) {
                log.warn("LogMiner probe failed during connection test; reporting none", e);
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
