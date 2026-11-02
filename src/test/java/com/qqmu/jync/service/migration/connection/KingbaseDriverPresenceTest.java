package com.qqmu.jync.service.migration.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Driver;

import org.junit.jupiter.api.Test;

/** The KingbaseES target is only usable if the bundled driver is on the classpath. */
class KingbaseDriverPresenceTest {

    @Test
    void theKingbaseDriverIsBundled() throws Exception {
        Class<?> type = Class.forName("com.kingbase8.Driver");

        assertThat(Driver.class).isAssignableFrom(type);

        Driver driver = (Driver) type.getDeclaredConstructor().newInstance();
        assertThat(driver.acceptsURL("jdbc:kingbase8://127.0.0.1:54321/orders")).isTrue();
        assertThat(driver.acceptsURL("jdbc:postgresql://127.0.0.1:5432/orders")).isFalse();
    }
}
