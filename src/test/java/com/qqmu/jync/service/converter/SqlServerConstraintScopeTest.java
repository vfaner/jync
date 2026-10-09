package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * P2: constraint toggles must be scoped to the synced tables — the old sp_MSforeachtable form
 * disabled/enabled constraints on every table of a shared database, and persisted beyond our
 * connection.
 */
class SqlServerConstraintScopeTest {

    private final SqlServerDialect dialect = new SqlServerDialect();

    @Test
    void disableAppliesOnlyToTheGivenTables() {
        String sql = dialect.getDisableConstraintsSql("dbo", List.of("T1", "T2"));

        assertThat(sql)
                .contains("ALTER TABLE [dbo].[T1] NOCHECK CONSTRAINT ALL")
                .contains("ALTER TABLE [dbo].[T2] NOCHECK CONSTRAINT ALL")
                .doesNotContain("sp_MSforeachtable");
    }

    @Test
    void enableValidatesDataAndAppliesOnlyToTheGivenTables() {
        String sql = dialect.getEnableConstraintsSql("dbo", List.of("T1"));

        assertThat(sql)
                .contains("ALTER TABLE [dbo].[T1] WITH CHECK CHECK CONSTRAINT ALL")
                .doesNotContain("sp_MSforeachtable");
    }

    @Test
    void anEmptyTableListProducesNoStatement() {
        assertThat(dialect.getDisableConstraintsSql("dbo", List.of())).isNull();
        assertThat(dialect.getEnableConstraintsSql("dbo", List.of())).isNull();
    }

    @Test
    void theUnscopedFormIsNotOffered() {
        // A database-wide toggle is intentionally unsupported.
        assertThat(dialect.getDisableConstraintsSql()).isNull();
        assertThat(dialect.getEnableConstraintsSql()).isNull();
    }
}
