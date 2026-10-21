package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * Tencent TDSQL (MySQL edition) and Alibaba PolarDB (MySQL edition) are MySQL-protocol
 * services: both vendor docs connect them with the standard Connector/J over
 * {@code jdbc:mysql://}, so the presets reuse the bundled driver and the MYSQL family.
 *
 * <p>Only the MySQL editions get a preset. The PostgreSQL editions use the PostgreSQL
 * preset, and PolarDB's Oracle-compatible edition needs the vendor driver
 * ({@code com.aliyun.polardb2.Driver}) through CUSTOM — a single alias must not pretend to
 * cover several protocols, or the connection fails with a protocol error instead of a clear
 * choice.
 */
class CloudMysqlPresetsTest {

    private final SqlDialectFactory factory = new SqlDialectFactory(
            List.of(new MySqlDialect(), new PostgresDialect(), new OpenGaussDialect(),
                    new GaussDbDialect(), new OracleDialect(), new SqlServerDialect(),
                    new Db2Dialect()),
            new GenericSqlDialect());

    @Test
    void tdsqlUsesTheBundledMysqlDriver() {
        assertThat(DatabaseType.TDSQL.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(DatabaseType.TDSQL.getUrlTemplate()).startsWith("jdbc:mysql://");
        assertThat(DatabaseType.TDSQL.getDefaultPort()).isEqualTo(3306);
        assertThat(DatabaseType.TDSQL.getFamily()).isEqualTo(DatabaseType.DialectFamily.MYSQL);
        assertThat(factory.forType(DatabaseType.TDSQL)).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void polarDbUsesTheBundledMysqlDriver() {
        assertThat(DatabaseType.POLARDB.getDriverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(DatabaseType.POLARDB.getUrlTemplate()).startsWith("jdbc:mysql://");
        assertThat(DatabaseType.POLARDB.getDefaultPort()).isEqualTo(3306);
        assertThat(DatabaseType.POLARDB.getFamily()).isEqualTo(DatabaseType.DialectFamily.MYSQL);
        assertThat(factory.forType(DatabaseType.POLARDB)).isInstanceOf(MySqlDialect.class);
    }

    @Test
    void bothKeepTheMysqlUrlFlagsTheSyncDependsOn() {
        for (DatabaseType type : List.of(DatabaseType.TDSQL, DatabaseType.POLARDB)) {
            String url = String.format(type.getUrlTemplate(), "h", type.getDefaultPort(), "db");
            assertThat(url).as("%s URL", type)
                    .contains("useAffectedRows=true")
                    .contains("rewriteBatchedStatements=true");
        }
    }
}
