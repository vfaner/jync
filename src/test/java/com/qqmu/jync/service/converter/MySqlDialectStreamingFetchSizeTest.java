package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * The MySQL streaming sentinel is a Connect/J contract, not a protocol one.
 *
 * <p>Connector/J streams only when the fetch size is {@link Integer#MIN_VALUE}; MariaDB's
 * own Connector/J 3.x rejects any negative value outright ("invalid fetch size"), which is
 * exactly how the first full load of {@code MariaDbPollingIT} failed. MariaDB streams on an
 * ordinary positive fetch size instead.
 */
class MySqlDialectStreamingFetchSizeTest {

    private final SqlDialectFactory factory = new SqlDialectFactory(
            List.of(new MySqlDialect()), new GenericSqlDialect());

    @Test
    void mariaDbKeepsTheConfiguredFetchSize() {
        assertThat(factory.forType(DatabaseType.MARIADB).streamingFetchSize(1000)).isEqualTo(1000);
    }

    @Test
    void mysqlKeepsTheConnectorJSentinel() {
        assertThat(factory.forType(DatabaseType.MYSQL).streamingFetchSize(1000))
                .isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void everyOtherMysqlFamilyProductKeepsTheConnectorJSentinel() {
        for (DatabaseType type : DatabaseType.values()) {
            if (type.getFamily() == DatabaseType.DialectFamily.MYSQL
                    && type != DatabaseType.MARIADB) {
                assertThat(factory.forType(type).streamingFetchSize(1000))
                        .as("%s connects through Connector/J", type)
                        .isEqualTo(Integer.MIN_VALUE);
            }
        }
    }
}
