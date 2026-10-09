package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * P1: user-supplied extra JDBC properties are attacker input. Keys that enable class
 * instantiation, local-file reads or stacked statements must be stripped instead of
 * appended verbatim, and values may not smuggle more properties past the per-key check.
 */
class DataSourceManagerExtraParamsTest {

    private final DataSourceManager manager =
            new DataSourceManager(null, null, new SyncProperties());

    private DatabaseConfig config(DatabaseType type) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("c");
        c.setType(type);
        c.setHost("h");
        c.setPort(type.getDefaultPort());
        c.setDatabaseName("db");
        return c;
    }

    @Test
    void benignParamsAreAppended() {
        DatabaseConfig c = config(DatabaseType.MYSQL);
        c.setExtraParams("connectTimeout=10000&socketTimeout=30000");

        String url = manager.buildJdbcUrl(c);

        assertThat(url).contains("&connectTimeout=10000&socketTimeout=30000");
    }

    @Test
    void dangerousKeysAreStripped() {
        DatabaseConfig c = config(DatabaseType.MYSQL);
        // Property injection (autoDeserialize + interceptor class), local infile,
        // stacked queries, class-instantiating factory.
        c.setExtraParams("connectTimeout=10000"
                + "&autoDeserialize=true"
                + "&queryInterceptors=com.evil.Interceptor"
                + "&allowLoadLocalInfile=true"
                + "&allowMultiQueries=true"
                + "&socketFactory=com.evil.Factory");

        String url = manager.buildJdbcUrl(c);

        assertThat(url).contains("connectTimeout=10000");
        assertThat(url).doesNotContain("autoDeserialize", "queryInterceptors",
                "allowLoadLocalInfile", "allowMultiQueries", "socketFactory");
    }

    @Test
    void dangerousKeysAreStrippedCaseInsensitively() {
        DatabaseConfig c = config(DatabaseType.MYSQL);
        c.setExtraParams("AutoDeserialize=true&ALLOWMULTIQUERIES=true&useSSL=false");

        String url = manager.buildJdbcUrl(c);

        assertThat(url).doesNotContain("AutoDeserialize", "ALLOWMULTIQUERIES");
        assertThat(url).contains("useSSL=false");
    }

    @Test
    void aValueSeparatedByTheOtherSeparatorIsStillSplitAndFiltered() {
        DatabaseConfig c = config(DatabaseType.MYSQL);
        // The value hides a ';'-separated second property. Tokenizing on BOTH separators
        // means the smuggled pair is extracted on its own and dropped, while the clean pair
        // next to it survives — the rendered MySQL URL never carries the ';'.
        c.setExtraParams("foo=bar;autoDeserialize=true&safe=1");

        String url = manager.buildJdbcUrl(c);

        assertThat(url).doesNotContain("autoDeserialize", ";");
        assertThat(url).contains("foo=bar", "safe=1");
    }

    @Test
    void semicolonStyleUrlsKeepTheirSeparatorWhenFiltering() {
        DatabaseConfig c = config(DatabaseType.SQLSERVER);
        c.setExtraParams("lockTimeout=3000;autoDeserialize=true");

        String url = manager.buildJdbcUrl(c);

        assertThat(url).endsWith(";lockTimeout=3000");
        assertThat(url).doesNotContain("autoDeserialize");
    }

    @Test
    void whenEveryExtraParamIsBlockedTheUrlIsUnchanged() {
        DatabaseConfig c = config(DatabaseType.MYSQL);
        c.setExtraParams("autoDeserialize=true");

        String before = manager.buildJdbcUrl(new ConfigCopy(c).withoutExtras());
        String after = manager.buildJdbcUrl(c);

        assertThat(after).isEqualTo(before);
    }

    /** Builds a config clone with no extras, so the baseline URL is generated independently. */
    private static class ConfigCopy {
        private final DatabaseConfig source;

        ConfigCopy(DatabaseConfig source) {
            this.source = source;
        }

        DatabaseConfig withoutExtras() {
            DatabaseConfig c = new DatabaseConfig();
            c.setName(source.getName());
            c.setType(source.getType());
            c.setHost(source.getHost());
            c.setPort(source.getPort());
            c.setDatabaseName(source.getDatabaseName());
            return c;
        }
    }
}
