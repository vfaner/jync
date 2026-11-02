package com.qqmu.jync.service.migration.connection;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.service.migration.MigrationGlob;

/** JDBC access to a KingbaseES target, using the bundled kingbase8 driver. */
@Component
public class KingbaseConnectionAdapter implements MigrationConnectionKindAdapter {

    private static final String URL_PREFIX = "jdbc:kingbase8://";
    private static final int MAX_IDENTIFIER_BYTES = 63;
    private static final Pattern CREDENTIAL_PARAM =
            Pattern.compile("(^|[?&;])(user|password)=", Pattern.CASE_INSENSITIVE);

    private final KingbaseConnectionFactory connectionFactory;

    public KingbaseConnectionAdapter(KingbaseConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Set<MigrationConnectionKind> kinds() {
        return Set.of(MigrationConnectionKind.KINGBASE);
    }

    @Override
    public MigrationConnectionEndpoint normalize(MigrationConnectionForm form) {
        String baseUrl = form.getBaseUrl() == null ? "" : form.getBaseUrl().trim();
        if (!baseUrl.toLowerCase(Locale.ROOT).startsWith(URL_PREFIX)) {
            throw new IllegalArgumentException(
                    "error.migration.connection.kingbase.url.invalid");
        }
        rejectCredentials(baseUrl);
        rejectCredentials(form.getExtraParams());

        String uriDatabase = databaseInUrl(baseUrl);
        String fieldDatabase = trimToNull(form.getDatabaseName());
        if (uriDatabase != null && fieldDatabase != null
                && !uriDatabase.equals(fieldDatabase)) {
            throw new IllegalArgumentException(
                    "error.migration.connection.kingbase.database.mismatch");
        }
        String schema = trimToNull(form.getSchemaName());
        if (schema != null && schema.getBytes(StandardCharsets.UTF_8).length
                > MAX_IDENTIFIER_BYTES) {
            throw new IllegalArgumentException(
                    "error.migration.connection.kingbase.schema.too.long");
        }
        return new MigrationConnectionEndpoint(baseUrl, fieldDatabase, schema,
                normalizeExtraParams(form.getExtraParams()));
    }

    @Override
    public void validateAuth(MigrationConnectionForm form, String rawPassword, String rawApiKey,
                             MigrationConnection existing) {
        if (form.getAuthType() != MigrationConnectionAuthType.BASIC) {
            throw new IllegalArgumentException(
                    "error.migration.connection.kingbase.auth.unsupported");
        }
        if (form.getUsername() == null || form.getUsername().isBlank()) {
            throw new IllegalArgumentException("error.migration.connection.username.required");
        }
        boolean hasRaw = rawPassword != null && !rawPassword.isEmpty();
        boolean hasStored = existing != null && existing.getPasswordEnc() != null;
        if (!hasRaw && !hasStored) {
            throw new IllegalArgumentException("error.migration.connection.password.required");
        }
    }

    @Override
    public MigrationConnectionTestResult test(MigrationConnection connection) {
        long start = System.nanoTime();
        try (Connection jdbc = connectionFactory.open(connection)) {
            return probe(jdbc, start);
        } catch (SQLException | RuntimeException e) {
            return failure(e, start);
        }
    }

    @Override
    public MigrationConnectionTestResult testTransient(MigrationConnection connection,
                                                       String plainPassword, String plainApiKey) {
        long start = System.nanoTime();
        try (Connection jdbc = connectionFactory.open(connection, plainPassword)) {
            return probe(jdbc, start);
        } catch (SQLException | RuntimeException e) {
            return failure(e, start);
        }
    }

    private static MigrationConnectionTestResult probe(Connection jdbc, long start)
            throws SQLException {
        DatabaseMetaData metaData = jdbc.getMetaData();
        return new MigrationConnectionTestResult(true, null,
                metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion(),
                currentSchema(jdbc), elapsedMs(start));
    }

    private static MigrationConnectionTestResult failure(Exception e, long start) {
        return new MigrationConnectionTestResult(false, e.getMessage(), null, null, null,
                elapsedMs(start));
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Override
    public List<MigrationDiscoveryItem> list(MigrationConnection connection, String pattern) {
        String schema = trimToNull(connection.getSchemaName());
        try (Connection jdbc = connectionFactory.open(connection)) {
            if (schema == null) {
                schema = currentSchema(jdbc);
            }
            DatabaseMetaData metaData = jdbc.getMetaData();
            List<MigrationDiscoveryItem> items = new ArrayList<>();
            try (ResultSet tables = metaData.getTables(null, schema, "%",
                    new String[] {"TABLE"})) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (name == null || name.isBlank()
                            || !MigrationGlob.matchesAny(name, pattern)) {
                        continue;
                    }
                    items.add(new MigrationDiscoveryItem(name, 0, "", "", ""));
                }
            }
            return items;
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** The connection's current schema; old drivers may not implement {@code getSchema}. */
    private static String currentSchema(Connection jdbc) {
        try {
            String schema = jdbc.getSchema();
            return schema == null || schema.isBlank() ? null : schema;
        } catch (SQLException | AbstractMethodError e) {
            return null;
        }
    }

    /** The database in the JDBC URL path, if any. */
    private static String databaseInUrl(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        int query = url.indexOf('?', scheme + 3);
        String pathPart = query >= 0 ? url.substring(0, query) : url;
        int slash = pathPart.indexOf('/', scheme + 3);
        if (slash < 0) {
            return null;
        }
        String database = pathPart.substring(slash + 1).trim();
        return database.isEmpty() ? null : database;
    }

    /** A JDBC URL or parameter list must never carry the secret in plain text. */
    private static void rejectCredentials(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (CREDENTIAL_PARAM.matcher(text).find()) {
            throw new IllegalArgumentException(
                    "error.migration.connection.kingbase.params.invalid");
        }
    }

    private static String normalizeExtraParams(String value) {
        String extra = trimToNull(value);
        if (extra == null) {
            return null;
        }
        while (extra.startsWith("?") || extra.startsWith("&")) {
            extra = extra.substring(1);
        }
        return extra.isEmpty() ? null : extra;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
