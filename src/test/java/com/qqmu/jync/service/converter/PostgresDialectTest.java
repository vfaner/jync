package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

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

    /**
     * PostgreSQL's CREATE OR REPLACE VIEW only accepts a query producing the same columns in
     * the same order and types; dropping, renaming or retyping a column answers "cannot drop
     * columns from view" and the change fails on every cycle, never retiring. Drop-then-create
     * is the only statement pair that can express those changes.
     */
    @Test
    @DisplayName("a PostgreSQL view is replaced by drop and create, not OR REPLACE")
    void aViewIsReplacedByDropAndCreate() {
        ViewMeta view = new ViewMeta();
        view.setName("v");
        view.setDefinition("SELECT id FROM t");

        assertThat(new PostgresDialect().getCreateViewSql(view, "s", DatabaseType.MYSQL))
                .containsExactly("DROP VIEW \"s\".\"v\"",
                        "CREATE VIEW \"s\".\"v\" AS SELECT id FROM t");
        assertThat(new PostgresDialect().dropsBeforeViewCreate()).isTrue();
        // openGauss and GaussDB share the limitation through the parent dialect.
        assertThat(new OpenGaussDialect().dropsBeforeViewCreate()).isTrue();
        assertThat(new GaussDbDialect().dropsBeforeViewCreate()).isTrue();
    }

    @Test
    void otherFamiliesLeaveTheSourceConnectionAlone() {
        assertThat(new MySqlDialect().requiresTransactionalSourceForStreaming()).isFalse();
        assertThat(new OracleDialect().requiresTransactionalSourceForStreaming()).isFalse();
        assertThat(new GenericSqlDialect().requiresTransactionalSourceForStreaming()).isFalse();
    }
}
