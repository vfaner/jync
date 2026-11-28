package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;

class EtlDatabaseIdentityTest {

    @Test
    void twoConnectionsToTheSameH2DatabaseMatch() throws Exception {
        try (Connection first = open("same");
             Connection second = open("same")) {
            assertThat(EtlDatabaseIdentity.sameDatabase(first, second,
                    config(), config())).isTrue();
        }
    }

    @Test
    void connectionsToDifferentH2DatabasesDoNotMatch() throws Exception {
        try (Connection first = open("left");
             Connection second = open("right")) {
            assertThat(EtlDatabaseIdentity.sameDatabase(first, second,
                    config(), config())).isFalse();
        }
    }

    private static Connection open(String name) throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:identity_" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS APP");
        }
        return connection;
    }

    private static DatabaseConfig config() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(DatabaseType.H2);
        return config;
    }
}
