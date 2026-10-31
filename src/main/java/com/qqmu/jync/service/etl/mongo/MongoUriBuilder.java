package com.qqmu.jync.service.etl.mongo;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import com.qqmu.jync.model.DatabaseConfig;

/**
 * Turns a {@link com.qqmu.jync.model.DatabaseType#MONGODB_NATIVE} connection into the standard
 * {@code mongodb://} URI the driver expects.
 *
 * <p>Two shapes are supported. Without a custom URL the host/port/database fields are
 * templated; with one, the pasted URI is kept (host, path and query) and only the missing
 * pieces — database path, credentials, extra options — are filled in. Credentials always come
 * from the encrypted password field: a password inside the URI itself is refused because it
 * would bypass the encryption-at-rest rule.
 */
public final class MongoUriBuilder {

    public static final int DEFAULT_PORT = 27017;

    private static final String MONGO = "mongodb://";
    private static final String MONGO_SRV = "mongodb+srv://";
    private static final String MASK = "****";

    /** Option names the driver accepts are ordinary identifiers; anything else is a typo. */
    private static final Pattern OPTION_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private MongoUriBuilder() {
    }

    /** Checks that {@code config} can be rendered; the error message is an i18n key. */
    public static void validate(DatabaseConfig config) {
        render(config, null);
    }

    /**
     * The real connection string, with {@code plainPassword} (already decrypted by the caller)
     * encoded into the userinfo section.
     */
    public static String build(DatabaseConfig config, String plainPassword) {
        return render(config, plainPassword);
    }

    /** A display-only URI: the password, when shown, is replaced by {@code ****}. */
    public static String preview(DatabaseConfig config) {
        return render(config, MASK);
    }

    private static String render(DatabaseConfig config, String password) {
        if (config == null) {
            throw new IllegalArgumentException("error.connection.type.required");
        }
        if (StringUtils.hasText(config.getCustomUrl())) {
            return renderCustom(config, password);
        }
        return renderGenerated(config, password);
    }

    private static String renderGenerated(DatabaseConfig config, String password) {
        String host = config.getHost() == null ? "" : config.getHost().trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("error.connection.host.required");
        }
        String database = config.getDatabaseName() == null
                ? "" : config.getDatabaseName().trim();
        if (database.isEmpty()) {
            throw new IllegalArgumentException("error.mongodb.native.database.required");
        }
        int port = config.getPort() == null ? DEFAULT_PORT : config.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("error.mongodb.uri.invalid");
        }

        StringBuilder uri = new StringBuilder(MONGO);
        appendCredentials(uri, config.getUsername(), password);
        uri.append(bracketIpv6(host)).append(':').append(port).append('/').append(encode(database));
        appendOptions(uri, null, config.getExtraParams());
        return uri.toString();
    }

    private static String renderCustom(DatabaseConfig config, String password) {
        String raw = config.getCustomUrl().trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        String scheme;
        String rest;
        if (lower.startsWith(MONGO)) {
            scheme = MONGO;
            rest = raw.substring(MONGO.length());
        } else if (lower.startsWith(MONGO_SRV)) {
            scheme = MONGO_SRV;
            rest = raw.substring(MONGO_SRV.length());
        } else {
            // Includes jdbc:mongodb:// — the JDBC form belongs to the Atlas preset, not here.
            throw new IllegalArgumentException("error.mongodb.uri.invalid");
        }

        String query = null;
        int question = rest.indexOf('?');
        if (question >= 0) {
            query = rest.substring(question + 1).trim();
            rest = rest.substring(0, question);
        }

        String database = null;
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            database = rest.substring(slash + 1).trim();
            rest = rest.substring(0, slash);
            if (database.isEmpty()) {
                database = null;
            }
        }

        String userInfo = null;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            userInfo = rest.substring(0, at);
            rest = rest.substring(at + 1);
            if (userInfo.isEmpty()) {
                throw new IllegalArgumentException("error.mongodb.uri.invalid");
            }
            if (userInfo.indexOf(':') >= 0) {
                // A password here would sidestep the encrypted-password field.
                throw new IllegalArgumentException("error.mongodb.uri.passwordInUrl");
            }
        }

        String hostPart = rest.trim();
        if (hostPart.isEmpty()) {
            throw new IllegalArgumentException("error.mongodb.uri.invalid");
        }
        if (MONGO_SRV.equals(scheme) && hostPart.indexOf(':') >= 0) {
            // SRV records already carry the port; an explicit one is rejected by the driver.
            throw new IllegalArgumentException("error.mongodb.uri.invalid");
        }

        StringBuilder uri = new StringBuilder(scheme);
        if (userInfo != null) {
            uri.append(userInfo);
            if (password != null && !password.isEmpty()) {
                // A username-only userinfo is the replica-set / SRV form the help text
                // recommends, so the encrypted password field must still reach the URI.
                uri.append(':').append(encode(password));
            }
            uri.append('@');
        } else {
            appendCredentials(uri, config.getUsername(), password);
        }
        uri.append(hostPart);
        if (database != null) {
            uri.append('/').append(database);
        } else if (StringUtils.hasText(config.getDatabaseName())) {
            uri.append('/').append(encode(config.getDatabaseName().trim()));
        } else {
            throw new IllegalArgumentException("error.mongodb.native.database.required");
        }
        appendOptions(uri, query, config.getExtraParams());
        return uri.toString();
    }

    private static void appendCredentials(StringBuilder uri, String username, String password) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        uri.append(encode(username.trim()));
        if (password != null && !password.isEmpty()) {
            uri.append(':').append(encode(password));
        }
        uri.append('@');
    }

    private static String bracketIpv6(String host) {
        if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            return "[" + host + "]";
        }
        return host;
    }

    /**
     * Appends the user's extra options. Keys already present in the pasted URI win, so a
     * custom URL stays authoritative; {@code user}/{@code password} are refused because they
     * would move the secret back into a URL.
     */
    private static void appendOptions(StringBuilder uri, String existingQuery,
                                      String extraParams) {
        String query = existingQuery == null ? "" : existingQuery;
        Map<String, String> extra = parseExtraParams(extraParams);

        StringBuilder added = new StringBuilder();
        for (Map.Entry<String, String> option : extra.entrySet()) {
            if (containsOptionKey(query, option.getKey())) {
                continue;
            }
            added.append(added.length() == 0 ? "" : "&")
                    .append(option.getKey()).append('=').append(encode(option.getValue()));
        }
        if (query.isEmpty() && added.length() == 0) {
            return;
        }
        uri.append('?').append(query);
        if (!query.isEmpty() && added.length() > 0) {
            uri.append('&');
        }
        uri.append(added);
    }

    private static Map<String, String> parseExtraParams(String extraParams) {
        Map<String, String> options = new LinkedHashMap<>();
        if (!StringUtils.hasText(extraParams)) {
            return options;
        }
        for (String token : extraParams.trim().split("[&;]")) {
            if (token.isBlank()) {
                continue;
            }
            int eq = token.indexOf('=');
            String key = (eq >= 0 ? token.substring(0, eq) : token).trim();
            String value = eq >= 0 ? token.substring(eq + 1).trim() : "";
            if (!OPTION_KEY.matcher(key).matches()
                    || "user".equalsIgnoreCase(key)
                    || "password".equalsIgnoreCase(key)) {
                throw new IllegalArgumentException("error.mongodb.uri.option.invalid");
            }
            options.put(key, value);
        }
        return options;
    }

    private static boolean containsOptionKey(String query, String key) {
        if (query.isEmpty()) {
            return false;
        }
        for (String token : query.split("&")) {
            int eq = token.indexOf('=');
            String existing = (eq >= 0 ? token.substring(0, eq) : token).trim();
            if (existing.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
