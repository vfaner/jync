package com.qqmu.jync.service.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PreDestroy;
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

    /** Connect timeout for ordinary pools; also the transient one the test button uses. */
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 15_000;

    /** Health dots give up quickly: the page is watching a dozen databases at once. */
    private static final long PROBE_CONNECT_TIMEOUT_MS = 3_000;

    /** How often a deferred borrow re-checks a full pool; a seat frees at a chunk boundary. */
    private static final long SEAT_POLL_INTERVAL_MS = 200L;

    private final DriverLoader driverLoader;
    private final CryptoUtil cryptoUtil;
    private final SyncProperties properties;

    /** This instance's own metadata-store URL; a sync connection may not point back at it. */
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url:}")
    private String metadataUrl;

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
        rejectNativeMongo(config);
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
     * Opens a connection for a sync cycle, waiting for a seat on a pool that another task
     * has fully checked out and deferring to the next fire if none frees in time.
     *
     * <p>Workers borrow per chunk, so a parallelism-4 full load takes every seat of a
     * five-connection pool at each boundary; a second task on the same configuration then
     * blocks for Hikari's fifteen-second timeout and fails its whole cycle. Waiting here
     * turns that error into a queue. The database actually being unreachable surfaces as a
     * {@link SQLException} either way — only a pool whose own counters show every seat in
     * use is contention, so a real failure is never downgraded.
     *
     * @throws PoolContentionException when no seat frees within {@code sync.pool-wait-ms}
     */
    public Connection getConnectionOrDefer(DatabaseConfig config) throws SQLException {
        long deadline = System.currentTimeMillis() + Math.max(0L, properties.getPoolWaitMs());
        while (true) {
            if (isPoolSaturated(config)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    throw new PoolContentionException(poolBusyMessage(config));
                }
                if (!sleepForSeat(Math.min(SEAT_POLL_INTERVAL_MS, remaining))) {
                    // Being interrupted here is a stop or a shutdown, not a fault: restore
                    // the flag and let the run be deferred rather than turning it into an
                    // ERROR row. The caller's stop checks run on the next boundary anyway.
                    Thread.currentThread().interrupt();
                    throw new PoolContentionException(poolBusyMessage(config));
                }
                continue;
            }
            try {
                return getConnection(config);
            } catch (SQLException e) {
                // The seat held at check time went away before the borrow: the pool is
                // full again, so this is still contention, not a broken database.
                if (isPoolSaturated(config)) {
                    throw new PoolContentionException(poolBusyMessage(config));
                }
                throw e;
            }
        }
    }

    /**
     * Whether every connection of the cached pool for {@code config} is checked out. An
     * unsaved or never-opened configuration has no pool yet and therefore no contention;
     * the borrow itself will create one.
     */
    public boolean isPoolSaturated(DatabaseConfig config) {
        if (config == null || config.getId() == null) {
            return false;
        }
        CachedDataSource cached = cache.get(config.getId());
        return cached != null && isSaturated(cached.dataSource);
    }

    /** Reads saturation from the pool's own counters — never guessed from error messages. */
    static boolean isSaturated(HikariDataSource pool) {
        if (pool == null) {
            return false;
        }
        com.zaxxer.hikari.HikariPoolMXBean bean = pool.getHikariPoolMXBean();
        int max = pool.getMaximumPoolSize();
        if (bean == null || max <= 0) {
            return false;
        }
        return bean.getActiveConnections() >= max;
    }

    private static boolean sleepForSeat(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String poolBusyMessage(DatabaseConfig config) {
        return "Connection pool for '" + (config.getName() == null ? config.getId() : config.getName())
                + "' is fully in use by another task";
    }

    /**
     * Creates a short-lived pool for an unsaved config (a test from an unsubmitted form).
     * Never cached — the caller MUST close the returned pool, ideally with
     * try-with-resources.
     */
    public HikariDataSource createTransientDataSource(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        rejectNativeMongo(config);
        return createDataSource(config, 2);
    }

    /**
     * A one-connection pool for the connections page's health dot.
     *
     * <p>Deliberately smaller and shorter than any other pool: the health check must never be
     * the reason a source database has one more idle session, and an unreachable host has to
     * give up in seconds instead of the test button's fifteen. Never cached; the caller closes
     * it with try-with-resources.
     */
    public HikariDataSource createProbeDataSource(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        rejectNativeMongo(config);
        return createDataSource(config, 1, PROBE_CONNECT_TIMEOUT_MS);
    }

    private HikariDataSource createDataSource(DatabaseConfig config, int poolSize) {
        return createDataSource(config, poolSize, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    private HikariDataSource createDataSource(DatabaseConfig config, int poolSize,
                                              long connectTimeoutMs) {
        String driverClass = resolveDriverClass(config);
        String jdbcUrl = buildJdbcUrl(config);

        // Register the driver before Hikari tries to resolve the class itself.
        ClassLoader driverClassLoader =
                driverLoader.ensureDriverLoaded(driverClass, config.getCustomJarPath());

        // HikariCP resolves driverClassName through the thread context class loader, falling
        // back to its own. A driver loaded from a user-supplied jar lives in a child
        // URLClassLoader that neither of those can see, so "Test connection" failed with
        // "Failed to load driver class ... in either of HikariConfig class loader or Thread
        // context classloader" for every custom driver (GaussDB, domestic databases, ...).
        // Point the context loader at the driver's loader while the pool is built; pool
        // creation instantiates the driver and resolves its class in this window.
        Thread thread = Thread.currentThread();
        ClassLoader previousLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(driverClassLoader);
        try {
            HikariConfig hikari = new HikariConfig();
            hikari.setJdbcUrl(jdbcUrl);
            hikari.setDriverClassName(driverClass);
            hikari.setUsername(config.getUsername());
            hikari.setPassword(cryptoUtil.decrypt(config.getPassword()));
            for (Map.Entry<String, String> property : driverProperties(config).entrySet()) {
                hikari.addDataSourceProperty(property.getKey(), property.getValue());
            }
            hikari.setPoolName("jync-" + (config.getName() == null ? "adhoc" : config.getName()));
            hikari.setMaximumPoolSize(poolSize);
            hikari.setMinimumIdle(1);
            hikari.setConnectionTimeout(connectTimeoutMs);
            hikari.setValidationTimeout(Math.min(5_000, connectTimeoutMs));
            hikari.setIdleTimeout(300_000);
            hikari.setMaxLifetime(1_800_000);
            // Fail fast on a bad connection instead of blocking the sync job for minutes.
            hikari.setInitializationFailTimeout(-1);
            hikari.setAutoCommit(true);

            log.debug("Creating pool for '{}' -> {}", config.getName(), jdbcUrl);
            return new HikariDataSource(hikari);
        } finally {
            thread.setContextClassLoader(previousLoader);
        }
    }

    public String resolveDriverClass(DatabaseConfig config) {
        rejectNativeMongo(config);
        if (config.getType() == DatabaseType.CUSTOM || StringUtils.hasText(config.getCustomDriver())) {
            if (!StringUtils.hasText(config.getCustomDriver())) {
                throw new IllegalArgumentException("error.custom.driver.required");
            }
            return config.getCustomDriver().trim();
        }
        if (config.getType() == null) {
            throw new IllegalArgumentException("error.connection.type.required");
        }
        String driver = config.getType().getDriverClassName();
        if (!StringUtils.hasText(driver)) {
            throw new IllegalArgumentException("driver.class.required");
        }
        return driver;
    }

    /**
     * P0-2: root-service URL for CDB mining, rebuilt from the configured host/port plus
     * the CDB name (the root service name by Oracle convention), extra params appended
     * through the same sanitizer as any other URL. Returns null when no safe derivation
     * exists — custom-URL sources (string surgery on a hand-pasted descriptor could
     * silently point mining at the wrong service) and non-Oracle types.
     */
    public String buildOracleRootUrl(DatabaseConfig config, String cdbName) {
        if (config.getType() != DatabaseType.ORACLE || StringUtils.hasText(config.getCustomUrl())) {
            return null;
        }
        int port = config.getPort() != null ? config.getPort() : DatabaseType.ORACLE.getDefaultPort();
        String host = StringUtils.hasText(config.getHost()) ? config.getHost() : "localhost";
        String url = String.format(DatabaseType.ORACLE.getUrlTemplate(), host, port, cdbName);
        return appendExtraParams(url, config);
    }

    /**
     * P0-2: opens the CDB$ROOT mining connection for {@code config} — root URL derived
     * from the CDB name, miner credentials decrypted here so the caller never handles
     * the plaintext. Throws when no safe root URL exists (custom-URL sources).
     */
    public Connection openOracleRootConnection(DatabaseConfig config, String cdbName)
            throws SQLException {
        String url = buildOracleRootUrl(config, cdbName);
        if (url == null) {
            throw new SQLException("Cannot derive a CDB$ROOT URL for connection '"
                    + config.getName() + "' — custom-URL sources must be reconfigured with"
                    + " host/port/service so the root service URL can be derived");
        }
        return openUnpooled(resolveDriverClass(config), config.getCustomJarPath(), url,
                config.getMinerUsername(), cryptoUtil.decrypt(config.getMinerPassword()));
    }

    /**
     * P0-2: one unpooled connection for the CDB$ROOT mining session. A consumer holds a
     * single long-lived LogMiner connection, so pooling buys nothing and would add pool
     * eviction edge cases; the caller owns the close. Driver readiness goes through the
     * same {@link DriverLoader} path as pooled connections.
     */
    public Connection openUnpooled(String driverClass, String jarPath, String jdbcUrl,
                                   String username, String password) throws SQLException {
        driverLoader.ensureDriverLoaded(driverClass, jarPath);
        return java.sql.DriverManager.getConnection(jdbcUrl, username, password);
    }

    /** Builds the JDBC URL from the template for the type, or uses the custom URL verbatim. */
    public String buildJdbcUrl(DatabaseConfig config) {
        rejectNativeMongo(config);
        String url;
        if (StringUtils.hasText(config.getCustomUrl())) {
            url = config.getCustomUrl().trim();
        } else {
            DatabaseType type = config.getType();
            if (type == DatabaseType.CUSTOM) {
                throw new IllegalArgumentException("error.custom.url.required");
            }
            if (type == null) {
                throw new IllegalArgumentException("error.connection.type.required");
            }
            String host = StringUtils.hasText(config.getHost()) ? config.getHost() : "localhost";
            String dbName = config.getDatabaseName() == null ? "" : config.getDatabaseName();
            // host/dbName are interpolated into the template; a separator in either would
            // smuggle driver properties past the per-key extraParams validation.
            rejectIdentifierSmuggling(host, config.getName());
            rejectIdentifierSmuggling(dbName, config.getName());
            // A named SQL Server instance has no fixed port; the driver resolves it via the
            // SQL Browser. Emitting the default-instance port 1433 there guarantees a timeout,
            // so a blank port is honored for products that declare a portless template.
            if (config.getPort() == null && type.allowsBlankPort()) {
                url = String.format(type.getPortlessUrlTemplate(), host, dbName);
            } else {
                int port = config.getPort() != null ? config.getPort() : type.getDefaultPort();
                url = String.format(type.getUrlTemplate(), host, port, dbName);
            }
        }
        url = disableTinyIntBooleanCollapse(appendExtraParams(url, config));
        url = disableStreamingResultTimeout(url);
        url = appendSocketTimeout(url);
        // The extraParams blacklist alone is not enough: a custom URL or an interpolated
        // host/dbName can carry dangerous properties directly. Validate the assembled URL.
        rejectDangerousUrlProperties(url, config.getName());
        rejectSelfMetadataStore(url, config.getName());
        return url;
    }

    /** Matches an existing {@code tinyInt1isBit} pair and nothing else in the query. */
    private static final java.util.regex.Pattern TINY_INT_BIT_PARAM =
            java.util.regex.Pattern.compile("(?i)([?&;])tinyInt1isBit=[^&;]*");

    /**
     * Forces {@code tinyInt1isBit=false} on MySQL-protocol URLs.
     *
     * <p>Connector/J's default makes the driver report a {@code TINYINT(1)} column as JDBC
     * BOOLEAN: {@code getObject} answers {@code Boolean}, so a status holding 5 is copied as
     * 1 into the target and never matches the source again. Turning the option off at the
     * connection level keeps the value numeric before any Jync code sees it, which covers
     * baseline, CDC, ETL, preview and structure metadata at once.
     *
     * <p>Only {@code jdbc:mysql:} (its DNS-SRV form {@code jdbc:mysql+srv:} included) and
     * {@code jdbc:mariadb:} are touched — the drivers that define the option. Vendor
     * drivers that merely speak the protocol (OceanBase, GBase)
     * keep their URLs untouched; their values are already guarded on the read path. An
     * existing {@code tinyInt1isBit} (from a custom URL or extra params) is rewritten in
     * place so an old {@code true} cannot win — matching the source column is not optional —
     * while every other parameter keeps its exact text. The query string is never
     * re-tokenized: a semicolon inside a value (a {@code sql_mode} list, for instance) is
     * data, not a separator.
     */
    private String disableTinyIntBooleanCollapse(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("jdbc:mysql:") && !lower.startsWith("jdbc:mysql+srv:")
                && !lower.startsWith("jdbc:mariadb:")) {
            return url;
        }
        java.util.regex.Matcher existing = TINY_INT_BIT_PARAM.matcher(url);
        if (existing.find()) {
            return existing.replaceAll("$1tinyInt1isBit=false");
        }
        // Same separator rule as appendExtraParams: SQL Server-style URLs that use ';'
        // without a '?' keep using ';', everything else uses '?'/'&'.
        String sep;
        if (url.indexOf('?') >= 0) {
            sep = endsWithParamSeparator(url) ? "" : "&";
        } else if (url.indexOf(';') >= 0) {
            sep = url.endsWith(";") ? "" : ";";
        } else {
            sep = "?";
        }
        return url + sep + "tinyInt1isBit=false";
    }

    private static boolean endsWithParamSeparator(String url) {
        return url.endsWith("?") || url.endsWith("&") || url.endsWith(";");
    }

    /**
     * Connector/J sets the server-side {@code net_write_timeout} to 600 seconds for streaming
     * result sets by default. A wide-table full load can pause the source read while the
     * target commits a batch; once that pause crosses ten minutes MySQL closes the
     * connection mid-read, and on Connector/J the follow-up statement cancel can surface as
     * {@code NativeSession.getCancelTimer() ... this.session is null}. The copy already has
     * its own bound ({@code sync.copy-timeout-seconds}), so disable this second, much shorter
     * server-side timeout. Only {@code jdbc:mysql:} (Connector/J) understands the property;
     * MariaDB's driver and the PostgreSQL family must not receive it. An explicit operator
     * value in the URL or extraParams wins.
     */
    private String disableStreamingResultTimeout(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mysql+srv:"))
                || lower.contains("nettimeoutforstreamingresults=")) {
            return url;
        }
        return url + (endsWithParamSeparator(url) ? "" : (url.indexOf('?') >= 0 ? "&" : "?"))
                + "netTimeoutForStreamingResults=0";
    }

    /**
     * Adds a driver-specific socket read timeout when {@code sync.jdbc-socket-timeout-ms} is set,
     * so a half-dead connection fails in bounded time instead of hanging on the OS TCP timeout.
     * 0 (the default) leaves every URL untouched. An explicit setting already in the URL wins.
     */
    private String appendSocketTimeout(String url) {
        if (url == null || properties == null || properties.getJdbcSocketTimeoutMs() <= 0) {
            return url;
        }
        long ms = properties.getJdbcSocketTimeoutMs();
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("sockettimeout=") || lower.contains("oracle.jdbc.readtimeout=")) {
            return url;   // respect an explicit operator setting
        }
        String param;
        if (lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mysql+srv:")
                || lower.startsWith("jdbc:mariadb:")) {
            param = "socketTimeout=" + ms;                  // Connector/J: milliseconds
        } else if (lower.startsWith("jdbc:postgresql:")) {
            param = "socketTimeout=" + ((ms + 999) / 1000); // pgjdbc: seconds, rounded up
        } else if (lower.startsWith("jdbc:oracle:")) {
            param = "oracle.jdbc.ReadTimeout=" + ms;        // Oracle thin: milliseconds
        } else {
            return url;   // no universal socket-timeout property for other drivers
        }
        String sep = endsWithParamSeparator(url) ? ""
                : (url.indexOf('?') >= 0 ? "&" : (url.indexOf(';') >= 0 ? ";" : "?"));
        return url + sep + param;
    }

    /**
     * JDBC properties the URL cannot carry for a given product. MongoDB's Atlas SQL driver
     * takes the database as a property ({@code database=...}) and rejects a database path in
     * the URL, so the form's database field is translated here instead of being templated into
     * the connection string; every other preset needs nothing beyond URL, user and password.
     */
    static Map<String, String> driverProperties(DatabaseConfig config) {
        rejectNativeMongo(config);
        if (config == null || config.getType() != DatabaseType.MONGODB
                || !StringUtils.hasText(config.getDatabaseName())) {
            return Map.of();
        }
        return Map.of("database", config.getDatabaseName().trim());
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
            "LOADBALANCESTRATEGY", "HA.LOADBALANCESTRATEGY", "PROPERTIESTRANSFORM",
            "JAVA_OBJECT_SERIALIZER", "DEFAULT_TABLE_ENGINE",
            // pgjdbc class-instantiation keys (the CVE-2022-21724 family) and Connector/J's
            // lifecycle interceptors: same "name a class from the URL" shape as the above.
            "SSLFACTORY", "SSLHOSTNAMEVERIFIER", "SSLPASSWORDCALLBACK",
            "CONNECTIONLIFECYCLEINTERCEPTORS");

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
     * Strict form of {@link #sanitizeExtraParam}: returns the rendered pair or throws. Used
     * by the assistant's connection-parameter card so a refused key is explained instead of
     * silently disappearing at the next connection open. Blocked keys are refused even if the
     * driver would ignore them, and values carrying separator or control characters are
     * refused because they would smuggle additional properties past the per-key validation.
     */
    public static String validatedExtraParam(String key, String value) {
        String k = key == null ? "" : key.trim();
        String v = value == null ? "" : value.trim();
        if (!StringUtils.hasText(k)
                || BLOCKED_PARAM_KEYS.contains(k.toUpperCase(java.util.Locale.ROOT))
                // H2's INIT/RUNSCRIPT are blocked in the assembled-URL check; keep them out
                // of the stored parameter string as well.
                || "INIT".equalsIgnoreCase(k) || "RUNSCRIPT".equalsIgnoreCase(k)) {
            throw new IllegalArgumentException("error.connection.url.unsafe");
        }
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            // '=', '&' and ';' would let one "key" split into an extra pair when the stored
            // string is parsed again: the card would show one parameter and the URL would
            // receive two. A key is a driver property name, never a separator.
            if (Character.isWhitespace(c) || c <= 0x1f || c == 0x7f
                    || c == '=' || c == '&' || c == ';') {
                throw new IllegalArgumentException("error.connection.url.unsafe");
            }
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '&' || c == ';' || c <= 0x1f || c == 0x7f) {
                throw new IllegalArgumentException("error.connection.url.unsafe");
            }
        }
        return v.isEmpty() ? k : k + "=" + v;
    }

    /**
     * Runtime path for one {@code key=value} pair: an unsafe pair is logged and dropped
     * rather than failing every connection open for a stored value that predates validation.
     */
    private String sanitizeExtraParam(String connectionName, String key, String value) {
        try {
            return validatedExtraParam(key, value);
        } catch (IllegalArgumentException e) {
            log.warn("Refused unsafe JDBC property '{}' on connection '{}'", key, connectionName);
            return null;
        }
    }

    /** Dangerous keys checked against the whole assembled URL, including H2 INIT/RUNSCRIPT. */
    private static final Set<String> BLOCKED_URL_KEYS;
    private static final java.util.regex.Pattern DANGEROUS_URL_PARAM;
    static {
        Set<String> keys = new java.util.HashSet<>(BLOCKED_PARAM_KEYS);
        keys.add("INIT");
        keys.add("RUNSCRIPT");
        BLOCKED_URL_KEYS = Set.copyOf(keys);
        String alternation = BLOCKED_URL_KEYS.stream()
                .map(java.util.regex.Pattern::quote)
                .reduce((a, b) -> a + "|" + b).orElse("");
        DANGEROUS_URL_PARAM = java.util.regex.Pattern.compile("(?i)[?&;]\\s*(" + alternation + ")\\s*=");
    }

    /** Refuses a host/database carrying URL-property separators or control characters. */
    private void rejectIdentifierSmuggling(String value, String connectionName) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ';' || c == '?' || c == '&' || c == '#' || c <= 0x1f || c == 0x7f) {
                log.warn("Refused JDBC host/database with separator or control characters on '{}'",
                        connectionName);
                throw new IllegalArgumentException("error.connection.url.unsafe");
            }
        }
    }

    /** Refuses dangerous driver properties anywhere in the assembled URL (custom or preset). */
    private void rejectDangerousUrlProperties(String url, String connectionName) {
        if (url == null) {
            return;
        }
        // MySQL-family drivers URL-decode query keys, so an encoded key (%61utoDeserialize)
        // would slip past a raw scan; check the percent-decoded form too.
        java.util.regex.Matcher m = DANGEROUS_URL_PARAM.matcher(url);
        if (!m.find()) {
            m = DANGEROUS_URL_PARAM.matcher(percentDecode(url));
            if (!m.find()) {
                return;
            }
        }
        log.warn("Refused unsafe JDBC URL property '{}' on connection '{}'",
                m.group(1), connectionName);
        throw new IllegalArgumentException("error.connection.url.unsafe");
    }

    /** Decodes %XX escapes once (matching the driver) so encoded property keys cannot hide. */
    private static String percentDecode(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.append((char) ((hi << 4) + lo));
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Refuses a sync connection that points back at this instance's own metadata store. */
    private void rejectSelfMetadataStore(String url, String connectionName) {
        String target = h2FilePath(url);
        String self = h2FilePath(metadataUrl);
        if (target != null && self != null && target.equals(self)) {
            log.warn("Refused a sync connection pointing at this instance's own metadata store "
                    + "on '{}'", connectionName);
            throw new IllegalArgumentException("error.connection.url.selfMetadata");
        }
    }

    /** Normalized local file path of an H2 store URL, or null when it cannot be this instance's. */
    private static String h2FilePath(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("jdbc:h2:") || lower.startsWith("jdbc:h2:mem:")) {
            return null;   // not H2, or an in-memory store distinct from the metadata file
        }
        String sub = url.substring("jdbc:h2:".length());
        if (sub.startsWith("tcp:") || sub.startsWith("ssl:")) {
            // The product's H2 preset is tcp://host:port/path; with the metadata store's
            // AUTO_SERVER=TRUE a loopback TCP URL reaches the very same file. Resolve the path
            // only when the server is this host, so a remote H2 box is not mistaken for it.
            sub = sub.substring(4);
            if (!sub.startsWith("//")) {
                return null;
            }
            sub = sub.substring(2);
            int slash = sub.indexOf('/');
            if (slash < 0) {
                return null;
            }
            String hostPort = sub.substring(0, slash);
            int colon = hostPort.indexOf(':');
            String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
            if (!isLoopbackHost(host)) {
                return null;
            }
            sub = sub.substring(slash + 1);
        } else if (sub.startsWith("file:")) {
            sub = sub.substring("file:".length());
        }
        int semi = sub.indexOf(';');
        if (semi >= 0) {
            sub = sub.substring(0, semi);
        }
        if (sub.isBlank()) {
            return null;
        }
        if (sub.startsWith("~")) {
            sub = System.getProperty("user.home") + sub.substring(1);
        }
        try {
            return java.nio.file.Path.of(sub).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
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

    /**
     * The native MongoDB preset is served by the Mongo client, not by a JDBC driver. Every
     * JDBC-shaped entry point refuses it here, so a stray caller gets a translated message
     * instead of an NPE on the null driver class or a URL template that is not JDBC.
     */
    private static void rejectNativeMongo(DatabaseConfig config) {
        if (config != null && config.effectiveType() == DatabaseType.MONGODB_NATIVE) {
            throw new IllegalArgumentException("error.mongodb.native.jdbcUnsupported");
        }
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
