package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * A SQL Server named instance is addressed without a port: the driver asks the SQL Browser
 * which port the instance listens on. Pinning 1433 — the default instance's port — makes
 * every connection to a named instance time out, so a blank port has to stay blank in the
 * URL for SQL Server only. Every other preset keeps its usual default when the field is left
 * empty.
 */
class DataSourceManagerJdbcUrlTest {

    private final DataSourceManager manager =
            new DataSourceManager(null, null, new SyncProperties());

    private DatabaseConfig config(DatabaseType type, String host, Integer port) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("c");
        c.setType(type);
        c.setHost(host);
        c.setPort(port);
        c.setDatabaseName("db");
        return c;
    }

    @Test
    void sqlServerBlankPortOmitsThePortSoTheInstanceCanBeResolved() {
        DatabaseConfig c = config(DatabaseType.SQLSERVER, "172.16.0.10\\SQLEXPRESS", null);

        String url = manager.buildJdbcUrl(c);

        assertThat(url)
                .startsWith("jdbc:sqlserver://172.16.0.10\\SQLEXPRESS;databaseName=db;")
                .doesNotContain(":1433");
    }

    @Test
    void sqlServerBlankPortKeepsAnInstanceNameFromExtraParams() {
        DatabaseConfig c = config(DatabaseType.SQLSERVER, "172.16.0.10", null);
        c.setExtraParams("instanceName=SQLEXPRESS");

        String url = manager.buildJdbcUrl(c);

        assertThat(url)
                .contains("jdbc:sqlserver://172.16.0.10;databaseName=db;")
                .contains(";instanceName=SQLEXPRESS")
                .doesNotContain(":1433");
    }

    @Test
    void sqlServerExplicitPortIsStillPinned() {
        DatabaseConfig c = config(DatabaseType.SQLSERVER, "172.16.0.10", 1450);

        String url = manager.buildJdbcUrl(c);

        assertThat(url).contains("jdbc:sqlserver://172.16.0.10:1450;databaseName=db;");
    }

    @Test
    void otherPresetsStillUseTheirDefaultPortWhenTheFieldIsBlank() {
        assertThat(manager.buildJdbcUrl(config(DatabaseType.MYSQL, "h", null)))
                .startsWith("jdbc:mysql://h:3306/db");
        assertThat(manager.buildJdbcUrl(config(DatabaseType.ORACLE, "h", null)))
                .isEqualTo("jdbc:oracle:thin:@//h:1521/db");
    }
}
