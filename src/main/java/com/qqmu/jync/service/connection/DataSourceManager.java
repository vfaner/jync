package com.qqmu.jync.service.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.util.CryptoUtil;
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
    private final SyncProperties properties;

    private final Map<Long, CachedDataSource> cache = new ConcurrentHashMap<>();

    public DataSourceManager(DriverLoader driverLoader, CryptoUtil cryptoUtil,
                             SyncProperties properties) {
        this.driverLoader = driverLoader;
        this.cryptoUtil = cryptoUtil;
        this.properties = properties;
    }

    /** Returns the pooled data source for a saved configuration, creating it on first use. */
    public DataSource getDataSource(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.getId() == null) {
            // An unsaved config has no identity to cache under; handing out an uncached pool
            // here leaks one per call (threads, sockets) because no caller can know to close
            // it. Transient tests must go through createTransientDataSource instead.
            throw new IllegalArgumentException(
                    "getDataSource requires a saved config; use createTransientDataSource for unsaved ones");
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
            // Create BEFORE touching the cache entry: if the rebuild throws (bad jar path,
            // unreachable custom driver class, ...), the old pool stays cached and keeps
            // serving. Closing first and creating second would leave a closed pool in the
            // cache under an unchanged fingerprint — every later call then fails with
            // "pool has been closed" until restart.
            HikariDataSource ds = createDataSource(config,
                    Math.max(2, properties.getMaxPoolSize()));
            CachedDataSource previous = cache.put(config.getId(), new CachedDataSource(fingerprint, ds));
            if (previous != null) {
                log.info("Connection settings for '{}' changed; rebuilding its pool", config.getName());
                closeQuietly(previous.dataSource);
            }
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

    /**
     * Creates a short-lived pool for an unsaved config (a test from an unsubmitted form).
     * Never cached — the caller MUST close the returned pool, ideally with
     * try-with-resources.
     */
    public HikariDataSource createTransientDataSource(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        return createDataSource(config, 2);
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
        hikari.setPoolName("jync-" + (config.getName() == null ? "adhoc" : config.getName()));
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

    /**
     * JDBC property keys that can turn a connection string into code execution or file
     * exfiltration. MySQL-family: {@code autoDeserialize} + {@code queryInterceptors}
     * instantiate attacker-named classes (gadget chains / property injection);
     * {@code allowLoadLocalInfile*} reads server-side files; {@code allowMultiQueries}
     * enables stacked statements; {@code socketFactory}/{@code sslSocketFactory}/
     * {@code loadBalanceStrategy} likewise name instantiable classes.
     */
    private static final Set<String> BLOCKED_PARAM_KEYS = Set.of(
            "AUTODESERIALIZE", "ALLOWLOADLOCALINFILE", "ALLOWURLINLOCALINFILE",
            "ALLOWLOADLOCALINFILEINPATH", "QUERYINTERCEPTORS", "STATEMENTINTERCEPTORS",
            "EXCEPTIONINTERCEPTORS", "ALLOWMULTIQUERIES", "CONNECTIONATTRIBUTES",
            "AUTHENTICATIONPLUGINS", "SOCKETFACTORY", "SSLSOCKETFACTORY",
            "LOADBALANCESTRATEGY", "HA.LOADBALANCESTRATEGY");

    private String appendExtraParams(String url, DatabaseConfig config) {
        String extra = config.getExtraParams();
        if (!StringUtils.hasText(extra)) {
            return url;
        }
        // Tokenize on BOTH separator styles, so a user who pasted '&' params into a ';'
        // URL still gets per-key validation instead of one opaque rejected blob.
        String[] tokens = extra.trim().split("[&;]");

        // SQL Server and DB2-style URLs use ';' separators; the rest use '?'/'&'.
        boolean semicolonUrl = url.contains(";") && !url.contains("?");
        String firstSep = url.contains("?") ? "&" : (semicolonUrl ? ";" : "?");
        String restSep = "?".equals(firstSep) ? "&" : firstSep;

        List<String> accepted = new ArrayList<>();
        for (String token : tokens) {
            int eq = token.indexOf('=');
            String key = (eq >= 0 ? token.substring(0, eq) : token).trim();
            String value = eq >= 0 ? token.substring(eq + 1).trim() : "";
            String safe = sanitizeExtraParam(config.getName(), key, value);
            if (safe != null) {
                accepted.add(safe);
            }
        }
        if (accepted.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        for (int i = 0; i < accepted.size(); i++) {
            sb.append(i == 0 ? firstSep : restSep).append(accepted.get(i));
        }
        return sb.toString();
    }

    /**
     * Validates one {@code key=value} pair, returning the rendered pair or null when it
     * must be dropped. Blocked keys are refused even if the driver would ignore them, and
     * values carrying separator or control characters are refused because they would
     * smuggle additional properties past the per-key validation.
     */
    private String sanitizeExtraParam(String connectionName, String key, String value) {
        if (!StringUtils.hasText(key) || BLOCKED_PARAM_KEYS.contains(key.toUpperCase())) {
            log.warn("Refused unsafe JDBC property '{}' on connection '{}'", key, connectionName);
            return null;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isWhitespace(c) || c <= 0x1f || c == 0x7f) {
                log.warn("Refused JDBC property with illegal characters in key '{}' on '{}'",
                        key, connectionName);
                return null;
            }
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&' || c == ';' || c <= 0x1f || c == 0x7f) {
                log.warn("Refused JDBC property '{}' — value contains separator/control"
                        + " characters on connection '{}'", key, connectionName);
                return null;
            }
        }
        return value.isEmpty() ? key : key + "=" + value;
    }

    /**
     * A fingerprint of everything that affects how a connection is opened. Any change
     * invalidates the cached pool.
     */
    private String fingerprint(DatabaseConfig c) {
        return String.join("\u0001",
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
