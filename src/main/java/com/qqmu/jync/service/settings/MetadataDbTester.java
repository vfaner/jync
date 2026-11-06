package com.qqmu.jync.service.settings;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.MetadataDbTestForm;

/**
 * Dials a candidate metadata database and reports what answered.
 *
 * <p>The input is typed by an administrator, which makes this a small SSRF surface: the scheme is
 * whitelisted to the four databases this application supports as a metadata store, the driver must
 * be that vendor's, and line breaks are refused so nothing can be smuggled into a JDBC URL. The
 * host is intentionally not restricted — the whole point is to reach a database on the customer's
 * network.
 *
 * <p>Timeouts are appended to the URL as vendor properties. {@code DriverManager.setLoginTimeout}
 * is deliberately not used: it is JVM-wide and would shorten every sync connection the
 * application opens, not just this test.
 */
@Component
public class MetadataDbTester {

    /** What the page renders. {@code envBlock} is empty when the connection failed. */
    public record Result(boolean success,
                         String message,
                         String product,
                         String version,
                         String envBlock) {
    }

    /** Seam for tests; production always goes through {@link DriverManager}. */
    @FunctionalInterface
    interface Connector {
        Connection open(String url, String username, String password) throws SQLException;
    }

    private static final int MAX_MESSAGE_CHARS = 300;

    private static final Map<String, Set<String>> DRIVERS_BY_PREFIX = new LinkedHashMap<>();

    static {
        DRIVERS_BY_PREFIX.put("jdbc:mysql:", Set.of("com.mysql.cj.jdbc.Driver"));
        DRIVERS_BY_PREFIX.put("jdbc:mariadb:", Set.of("org.mariadb.jdbc.Driver"));
        DRIVERS_BY_PREFIX.put("jdbc:postgresql:", Set.of("org.postgresql.Driver"));
        DRIVERS_BY_PREFIX.put("jdbc:oracle:thin:",
                Set.of("oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver"));
    }

    private final Connector connector;

    public MetadataDbTester() {
        this(DriverManager::getConnection);
    }

    MetadataDbTester(Connector connector) {
        this.connector = connector;
    }

    /** @throws IllegalArgumentException with an i18n key when the input is not a legal probe */
    public Result test(MetadataDbTestForm form) {
        String url = trim(form.getUrl());
        if (url.isEmpty()) {
            throw new IllegalArgumentException("error.dbTest.urlRequired");
        }
        if (hasLineBreak(url)) {
            throw new IllegalArgumentException("error.dbTest.urlInvalid");
        }
        String prefix = matchingPrefix(url);
        if (prefix == null) {
            throw new IllegalArgumentException("error.dbTest.urlInvalid");
        }

        String driver = trim(form.getDriverClass());
        if (driver.isEmpty()) {
            throw new IllegalArgumentException("error.dbTest.driverRequired");
        }
        if (hasLineBreak(driver) || !knownDriver(driver)) {
            throw new IllegalArgumentException("error.dbTest.driverUnknown");
        }
        if (!DRIVERS_BY_PREFIX.get(prefix).contains(driver)) {
            throw new IllegalArgumentException("error.dbTest.driverMismatch");
        }

        String username = trim(form.getUsername());
        String password = form.getPassword() == null ? "" : form.getPassword();
        String probeUrl = withTimeouts(url, prefix);

        try (Connection connection = connector.open(probeUrl, username, password)) {
            DatabaseMetaData meta = connection.getMetaData();
            String product = meta == null ? "" : nullToEmpty(meta.getDatabaseProductName());
            String version = meta == null ? "" : nullToEmpty(meta.getDatabaseProductVersion());
            return new Result(true, "msg.systemConfig.dbTest.ok", product, version,
                    envBlock(url, username, password, driver));
        } catch (SQLTimeoutException e) {
            return failure("error.dbTest.timeout");
        } catch (SQLException e) {
            // Drivers quote the password in connection errors; the page must not echo it back.
            return failure(redact(e.getMessage(), password));
        }
    }

    private static Result failure(String message) {
        return new Result(false, message, "", "", "");
    }

    private static String matchingPrefix(String url) {
        for (String prefix : DRIVERS_BY_PREFIX.keySet()) {
            if (url.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return prefix;
            }
        }
        return null;
    }

    private static boolean knownDriver(String driver) {
        for (Set<String> drivers : DRIVERS_BY_PREFIX.values()) {
            if (drivers.contains(driver)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    /** Vendor-appropriate timeouts, added only when the operator did not set them already. */
    static String withTimeouts(String url, String prefix) {
        Map<String, String> params = new LinkedHashMap<>();
        if ("jdbc:postgresql:".equals(prefix)) {
            // PostgreSQL counts seconds.
            params.put("connectTimeout", "5");
            params.put("socketTimeout", "5");
        } else if ("jdbc:oracle:thin:".equals(prefix)) {
            params.put("oracle.net.CONNECT_TIMEOUT", "5000");
            params.put("oracle.jdbc.ReadTimeout", "5000");
        } else {
            // MySQL and MariaDB count milliseconds.
            params.put("connectTimeout", "5000");
            params.put("socketTimeout", "5000");
        }

        StringBuilder probe = new StringBuilder(url);
        char separator = url.indexOf('?') >= 0 ? '&' : '?';
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (url.contains(param.getKey() + "=")) {
                continue;
            }
            probe.append(separator).append(param.getKey()).append('=').append(param.getValue());
            separator = '&';
        }
        return probe.toString();
    }

    /**
     * A POSIX-shell assignment block. Single quotes keep every character literal; the only
     * character single quotes cannot hold is an escaped single quote, written {@code '\''}.
     */
    static String envBlock(String url, String username, String password, String driver) {
        return "SPRING_DATASOURCE_URL=" + quote(url) + "\n"
                + "SPRING_DATASOURCE_USERNAME=" + quote(username) + "\n"
                + "SPRING_DATASOURCE_PASSWORD=" + quote(password) + "\n"
                + "SPRING_DATASOURCE_DRIVER_CLASS_NAME=" + quote(driver);
    }

    private static String quote(String value) {
        return "'" + (value == null ? "" : value).replace("'", "'\\''") + "'";
    }

    private static String redact(String message, String password) {
        if (message == null || message.isBlank()) {
            return "error.dbTest.failed";
        }
        String cleaned = message.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (!password.isEmpty()) {
            cleaned = cleaned.replace(password, "***");
        }
        return cleaned.length() <= MAX_MESSAGE_CHARS
                ? cleaned : cleaned.substring(0, MAX_MESSAGE_CHARS);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
