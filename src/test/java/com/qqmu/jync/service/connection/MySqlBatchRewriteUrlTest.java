package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * Connector/J's {@code executeBatch()} sends each statement of a batch on its own unless the
 * URL asks for the rewrite, so a MySQL target pays one network round trip per row however
 * large a batch Jync hands it. Every write Jync makes to MySQL is an
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}, a shape the rewrite handles, so the flag
 * belongs in the preset rather than in each operator's extra properties.
 */
class MySqlBatchRewriteUrlTest {

    private final DataSourceManager manager =
            new DataSourceManager(null, null, new SyncProperties());

    private static DatabaseConfig config(DatabaseType type) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("target");
        c.setType(type);
        c.setHost("db");
        c.setPort(type.getDefaultPort());
        c.setDatabaseName("shop");
        return c;
    }

    @Test
    void mysqlPresetAsksConnectorJToRewriteBatches() {
        String url = manager.buildJdbcUrl(config(DatabaseType.MYSQL));

        assertThat(url).contains("rewriteBatchedStatements=true");
        // Once: a duplicate key in one URL is a driver-dependent coin flip over which wins.
        assertThat(url.split("rewriteBatchedStatements", -1)).hasSize(2);
    }

    @Test
    void tidbPresetUsesTheSameDriverAndTheSameRewrite() {
        String url = manager.buildJdbcUrl(config(DatabaseType.TIDB));

        assertThat(url).contains("rewriteBatchedStatements=true");
        assertThat(url.split("rewriteBatchedStatements", -1)).hasSize(2);
    }

    @Test
    void otherTargetsAreNotGivenAMysqlOnlyProperty() {
        assertThat(manager.buildJdbcUrl(config(DatabaseType.POSTGRESQL)))
                .doesNotContain("rewriteBatchedStatements");
        assertThat(manager.buildJdbcUrl(config(DatabaseType.ORACLE)))
                .doesNotContain("rewriteBatchedStatements");
    }
}
