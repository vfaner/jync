package com.synctool.service.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.util.CryptoUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Owns the pooled {@link DataSource} for every configured database.
 *
 * <p>Pools are cached by config id and keyed additionally by a fingerprint of the
 * connection settings, so editing a connection transparently replaces its pool instead of
 * leaving stale credentials in use.
 */
@Service
@Slf4j
public class DataSourceManager {

    private final DriverLoader driverLoader;
    private final CryptoUtil cryptoUtil;

    private final Map<Long, CachedDataSource> cache = new ConcurrentHashMap<>();

    public DataSourceManager(DriverLoader driverLoader, CryptoUtil cryptoUtil) {
        this.driverLoader = driverLoader;
        this.cryptoUtil = cryptoUtil;
    }

    /** Returns the pooled data source for a saved configuration, creating it on first use. */
    public DataSource getDataSource(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.getId() == null) {
            // Unsaved config (e.g. a test from an unsubmitted form): build a throwaway pool.
            return createDataSource(config, 2);
        }
        String fingerprint = fingerprint(config);
        CachedDataSource cached = cache.get(config.getId());
        if (cached != null && cached.fingerprint.equals(fingerprint)) {
            return cached.dataSource;
        }
        synchronized (this) {
            cached = cache.get(config.getId());
            if (cached != null && cached.fingerprint.equals(fingerprint)) {
                return cached.dataSource;
            }
            if (cached != null) {
                log.info("Connection settings for '{}' changed; rebuilding its pool", config.getName());
                closeQuietly(cached.dataSource);
            }
            HikariDataSource ds = createDataSource(config, 10);
            cache.put(config.getId(), new CachedDataSource(fingerprint, ds));
            return ds;
        }
    }

    /**
     * Opens a connection. The caller is responsible for closing it; use
     * try-with-resources so it returns to the pool.
     */
    public Connection getConnection(DatabaseConfig config) throws SQLException {
        return getDataSource(config).getConnection();
    }

    private HikariDataSource createDataSource(DatabaseConfig config, int poolSize) {
        String driverClass = resolveDriverClass(config);
        String jdbcUrl = buildJdbcUrl(config);

        // Register the driver before Hikari tries to resolve the class itself.
        driverLoader.ensureDriverLoaded(driverClass, config.getCustomJarPath());

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setDriverClassName(driverClass);
        hikari.setUsername(config.getUsername());
        hikari.setPassword(cryptoUtil.decrypt(config.getPassword()));
        hikari.setPoolName("synctool-" + (config.getName() == null ? "adhoc" : config.getName()));
        hikari.setMaximumPoolSize(poolSize);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(15_000);
        hikari.setValidationTimeout(5_000);
        hikari.setIdleTimeout(300_000);
        hikari.setMaxLifetime(1_800_000);
        // Fail fast on a bad connection instead of blocking the sync job for minutes.
        hikari.setInitializationFailTimeout(-1);
        hikari.setAutoCommit(true);

        log.debug("Creating pool for '{}' -> {}", config.getName(), jdbcUrl);
        return new HikariDataSource(hikari);
    }

    public String resolveDriverClass(DatabaseConfig config) {
        if (config.getType() == DatabaseType.CUSTOM || StringUtils.hasText(config.getCustomDriver())) {
            if (!StringUtils.hasText(config.getCustomDriver())) {
                throw new IllegalArgumentException("custom.driver.required");
            }
            return config.getCustomDriver().trim();
        }
        String driver = config.getType().getDriverClassName();
        if (!StringUtils.hasText(driver)) {
            throw new IllegalArgumentException("driver.class.required");
        }
        return driver;
    }

    /** Builds the JDBC URL from the template for the type, or uses the custom URL verbatim. */
    public String buildJdbcUrl(DatabaseConfig config) {
        if (StringUtils.hasText(config.getCustomUrl())) {
            return appendExtraParams(config.getCustomUrl().trim(), config);
        }
        DatabaseType type = config.getType();
        if (type == DatabaseType.CUSTOM) {
            throw new IllegalArgumentException("custom.url.required");
        }
        String template = type.getUrlTemplate();
        int port = config.getPort() != null ? config.getPort() : type.getDefaultPort();
        String host = StringUtils.hasText(config.getHost()) ? config.getHost() : "localhost";
        String dbName = config.getDatabaseName() == null ? "" : config.getDatabaseName();
        return appendExtraParams(String.format(template, host, port, dbName), config);
    }

    private String appendExtraParams(String url, DatabaseConfig config) {
        String extra = config.getExtraParams();
        if (!StringUtils.hasText(extra)) {
            return url;
        }
        String trimmed = extra.trim();
        // SQL Server and DB2-style URLs use ';' separators; the rest use '?'/'&'.
        char separator = url.contains(";") && !url.contains("?") ? ';'
                : (url.contains("?") ? '&' : '?');
        if (trimmed.charAt(0) == separator) {
            return url + trimmed;
        }
        return url + separator + trimmed;
    }

    /**
     * A fingerprint of everything that affects how a connection is opened. Any change
     * invalidates the cached pool.
     */
    private String fingerprint(DatabaseConfig c) {
        return String.join("",
                String.valueOf(c.getType()),
                String.valueOf(c.getHost()),
                String.valueOf(c.getPort()),
                String.valueOf(c.getDatabaseName()),
                String.valueOf(c.getSchemaName()),
                String.valueOf(c.getUsername()),
                String.valueOf(c.getPassword()),
                String.valueOf(c.getCustomUrl()),
                String.valueOf(c.getCustomDriver()),
                String.valueOf(c.getCustomJarPath()),
                String.valueOf(c.getExtraParams()));
    }

    /** Drops the cached pool for a configuration, e.g. after it is deleted. */
    public void evict(Long configId) {
        CachedDataSource removed = cache.remove(configId);
        if (removed != null) {
            closeQuietly(removed.dataSource);
            log.info("Evicted connection pool for config id {}", configId);
        }
    }

    public void evictAll() {
        cache.keySet().forEach(this::evict);
    }

    private void closeQuietly(DataSource ds) {
        if (ds instanceof HikariDataSource) {
            try {
                ((HikariDataSource) ds).close();
            } catch (Exception e) {
                log.debug("Error closing pool: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Closing {} target database connection pools", cache.size());
        evictAll();
    }

    private static final class CachedDataSource {
        final String fingerprint;
        final HikariDataSource dataSource;

        CachedDataSource(String fingerprint, HikariDataSource dataSource) {
            this.fingerprint = fingerprint;
            this.dataSource = dataSource;
        }
    }
}
