package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

/**
 * Connector/J's {@code tinyInt1isBit} default makes the driver report a MySQL
 * {@code TINYINT(1)} as a boolean: {@code getObject} answers {@code Boolean} and a status
 * holding 5 lands on the target as 1. Disabling the option at the connection level removes
 * the collapse before any Jync code sees the value, so every path (baseline, CDC, ETL,
 * preview, structure metadata) gets the plain number.
 *
 * <p>Only URLs the known drivers accept the property on are touched: {@code jdbc:mysql:}
 * (Connector/J, the bundled presets that reuse it, and the {@code jdbc:mysql+srv:} DNS
 * form) and {@code jdbc:mariadb:} (MariaDB Connector/J). Vendor drivers that merely speak
 * the protocol keep their URLs untouched; the read-side guard still covers them.
 */
class MySqlTinyInt1UrlTest {

    private final DataSourceManager manager =
            new DataSourceManager(null, null, new SyncProperties());

    private static DatabaseConfig config(DatabaseType type) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("c");
        c.setType(type);
        c.setHost("db");
        c.setPort(type.getDefaultPort());
        c.setDatabaseName("shop");
        return c;
    }

    private static DatabaseConfig custom(String url) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName("custom");
        c.setType(DatabaseType.CUSTOM);
        c.setCustomUrl(url);
        return c;
    }

    @Test
    void mysqlPresetTurnsTheBooleanCollapseOff() {
        String url = manager.buildJdbcUrl(config(DatabaseType.MYSQL));

        assertThat(url).contains("tinyInt1isBit=false");
        // Once: two values for one key is a driver-dependent coin flip over which wins.
        assertThat(url.split("tinyInt1isBit", -1)).hasSize(2);
    }

    @Test
    void mariadbPresetTurnsTheBooleanCollapseOff() {
        assertThat(manager.buildJdbcUrl(config(DatabaseType.MARIADB)))
                .contains("tinyInt1isBit=false");
    }

    @Test
    void dorisReachesConnectorJThroughTheSameUrl() {
        assertThat(manager.buildJdbcUrl(config(DatabaseType.DORIS)))
                .contains("tinyInt1isBit=false");
    }

    @Test
    void aUserSuppliedMysqlUrlGainsTheOptionWithoutDuplicatingIt() {
        String url = manager.buildJdbcUrl(custom("jdbc:mysql://db:3306/shop?useSSL=false"));

        assertThat(url).contains("tinyInt1isBit=false")
                .contains("useSSL=false");
        assertThat(url.split("tinyInt1isBit", -1)).hasSize(2);
    }

    @Test
    void anExplicitTrueIsForcedToFalse() {
        // 用户或旧配置里写了 true 也不能让数据继续被折叠：内容一致优先于该开关。
        String url = manager.buildJdbcUrl(custom(
                "jdbc:mysql://db:3306/shop?tinyInt1isBit=true&useSSL=false"));

        assertThat(url).contains("tinyInt1isBit=false").doesNotContain("tinyInt1isBit=true");
        assertThat(url.split("tinyInt1isBit", -1)).hasSize(2);
    }

    @Test
    void extraParamsCannotReintroduceTheCollapse() {
        DatabaseConfig c = config(DatabaseType.MYSQL);
        c.setExtraParams("tinyInt1isBit=true");

        String url = manager.buildJdbcUrl(c);

        assertThat(url).contains("tinyInt1isBit=false").doesNotContain("tinyInt1isBit=true");
        assertThat(url.split("tinyInt1isBit", -1)).hasSize(2);
    }

    @Test
    void theDnsSrvUrlFormIsCoveredToo() {
        // Connector/J 的 SRV 写法（Kubernetes / Aurora）没有 "jdbc:mysql://" 前缀，
        // 只按 "jdbc:mysql:" 判断会漏掉。
        String url = manager.buildJdbcUrl(custom("jdbc:mysql+srv://_mysql._tcp.example.com/shop"));

        assertThat(url).contains("tinyInt1isBit=false");
    }

    @Test
    void aSemicolonInsideAValueIsNotADelimiter() {
        // 查询串的值本身可能带分号（sql_mode 列表、sessionVariables 等）。
        // 为了拿掉一个开关而重排整段查询串，会把这类值改坏。
        String url = manager.buildJdbcUrl(custom("jdbc:mysql://db:3306/shop?"
                + "sessionVariables=sql_mode='STRICT_TRANS_TABLES;NO_ENGINE_SUBSTITUTION'"));

        assertThat(url)
                .contains("sessionVariables=sql_mode='STRICT_TRANS_TABLES;NO_ENGINE_SUBSTITUTION'")
                .contains("tinyInt1isBit=false");
    }

    @Test
    void otherProductsKeepTheirUrlsUntouched() {
        assertThat(manager.buildJdbcUrl(config(DatabaseType.POSTGRESQL)))
                .doesNotContain("tinyInt1isBit");
        assertThat(manager.buildJdbcUrl(config(DatabaseType.ORACLE)))
                .doesNotContain("tinyInt1isBit");
        // 厂商自有驱动（OceanBase / GBase）只走协议兼容，URL 里不加它未必认识的参数。
        assertThat(manager.buildJdbcUrl(config(DatabaseType.OCEANBASE)))
                .doesNotContain("tinyInt1isBit");
        assertThat(manager.buildJdbcUrl(config(DatabaseType.GBASE)))
                .doesNotContain("tinyInt1isBit");
        assertThat(manager.buildJdbcUrl(custom("jdbc:postgresql://db:5432/shop")))
                .doesNotContain("tinyInt1isBit");
    }
}
