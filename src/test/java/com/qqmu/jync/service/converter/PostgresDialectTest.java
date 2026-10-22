package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Whether a source read needs an explicit transaction is a driver fact, not a preference:
 * the PostgreSQL driver buffers a result set client-side unless autocommit is off, so its
 * fetch size only takes effect inside a transaction. openGauss and GaussDB share the
 * protocol and inherit the answer; every other family keeps the connection as it came.
 */
class PostgresDialectTest {

    @Test
    void thePostgresFamilyNeedsAnExplicitSourceTransaction() {
        assertThat(new PostgresDialect().requiresTransactionalSourceForStreaming()).isTrue();
        assertThat(new OpenGaussDialect().requiresTransactionalSourceForStreaming()).isTrue();
        assertThat(new GaussDbDialect().requiresTransactionalSourceForStreaming()).isTrue();
    }

    @Test
    void otherFamiliesLeaveTheSourceConnectionAlone() {
        assertThat(new MySqlDialect().requiresTransactionalSourceForStreaming()).isFalse();
        assertThat(new OracleDialect().requiresTransactionalSourceForStreaming()).isFalse();
        assertThat(new GenericSqlDialect().requiresTransactionalSourceForStreaming()).isFalse();
    }
}
