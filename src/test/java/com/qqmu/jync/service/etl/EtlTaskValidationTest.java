package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

class EtlTaskValidationTest {

    @Test
    void localhostAndLoopbackAddressesAreTheSameEndpoint() {
        DatabaseConfig source = config("localhost");
        DatabaseConfig target = config("127.0.0.1");

        assertThatThrownBy(() -> EtlTaskValidation.rejectSameEndpoint(source, target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.same.database");
    }

    @Test
    void differentDatabasesOnTheSameHostAreAllowed() {
        DatabaseConfig source = config("localhost");
        DatabaseConfig target = config("localhost");
        target.setDatabaseName("other_db");

        assertThatCode(() -> EtlTaskValidation.rejectSameEndpoint(source, target))
                .doesNotThrowAnyException();
    }

    private static DatabaseConfig config(String host) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.MYSQL);
        config.setHost(host);
        config.setPort(3306);
        config.setDatabaseName("shop");
        config.setSchemaName("shop");
        return config;
    }
}
