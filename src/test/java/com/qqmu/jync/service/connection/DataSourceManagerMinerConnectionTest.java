package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.Connection;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * P0-2: the CDB mining connection is deliberately NOT pooled — one consumer holds one
 * long-lived LogMiner connection, so a Hikari pool would add eviction/lifetime edge
 * cases for zero reuse. The root URL is derived from the configured host/port plus the
 * CDB name; custom-URL sources cannot be rewritten safely and must be refused (null),
 * never silently pointed at the wrong service.
 */
class DataSourceManagerMinerConnectionTest {

    private final DriverLoader driverLoader = mock(DriverLoader.class);
    private final DataSourceManager manager =
            new DataSourceManager(driverLoader, null, new SyncProperties());

    private DatabaseConfig oracle() {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("ora");
        c.setType(DatabaseType.ORACLE);
        c.setHost("db.internal");
        c.setPort(1521);
        c.setDatabaseName("FREEPDB1");
        return c;
    }

    @Test
    void rootUrlIsRebuiltFromHostPortAndCdbName() {
        String url = manager.buildOracleRootUrl(oracle(), "FREE");

        assertThat(url).isEqualTo("jdbc:oracle:thin:@//db.internal:1521/FREE");
    }

    @Test
    void rootUrlCarriesSanitizedExtraParams() {
        DatabaseConfig c = oracle();
        c.setExtraParams("oracle.net.CONNECT_TIMEOUT=5000&autoDeserialize=true");

        String url = manager.buildOracleRootUrl(c, "FREE");

        assertThat(url).contains("oracle.net.CONNECT_TIMEOUT=5000");
        assertThat(url).doesNotContain("autoDeserialize");
    }

    @Test
    void rootUrlIsNullForCustomUrlSources() {
        // A hand-pasted descriptor (TNS alias, SID form, wallet) cannot be rewritten by
        // string surgery — refuse so the probe can say "configure a root URL explicitly".
        DatabaseConfig c = oracle();
        c.setCustomUrl("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=h))(PORT=1521))"
                + "(CONNECT_DATA=(SERVICE_NAME=FREEPDB1)))");

        assertThat(manager.buildOracleRootUrl(c, "FREE")).isNull();
    }

    @Test
    void rootUrlIsNullForNonOracleTypes() {
        DatabaseConfig c = oracle();
        c.setType(DatabaseType.MYSQL);

        assertThat(manager.buildOracleRootUrl(c, "FREE")).isNull();
    }

    @Test
    void openUnpooledReturnsAWorkingConnection() throws Exception {
        // H2 在测试 classpath 上:验证 openUnpooled 的真实链路(驱动就绪 + DriverManager)
        // 而不仅是 mock 拼线。矿工连接不池化正是为了这条直路。
        try (Connection conn = manager.openUnpooled(
                "org.h2.Driver", null, "jdbc:h2:mem:miner_unpooled;DB_CLOSE_DELAY=-1",
                "sa", "")) {
            assertThat(conn.isValid(2)).isTrue();
        }
        verify(driverLoader).ensureDriverLoaded("org.h2.Driver", null);
    }
}
