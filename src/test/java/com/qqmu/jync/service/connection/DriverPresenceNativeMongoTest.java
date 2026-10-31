package com.qqmu.jync.service.connection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * The native MongoDB preset has no JDBC driver class at all (the bundled
 * {@code mongodb-driver-sync} is not a {@link java.sql.Driver}). The connection form must
 * therefore never demand a user-supplied JDBC jar for it, and the save path must not fall
 * into {@code error.driver.absent}.
 */
class DriverPresenceNativeMongoTest {

    @Test
    void nativeMongoNeverRequiresAnExternalJdbcJar() {
        assertThat(DriverPresence.externalJarRequired(DatabaseType.MONGODB_NATIVE, null)).isFalse();
        assertThat(DriverPresence.externalJarRequired(DatabaseType.MONGODB_NATIVE, "  ")).isFalse();
        assertThat(DriverPresence.externalJarRequired(DatabaseType.MONGODB_NATIVE, "com.example.X"))
                .isFalse();
    }
}
