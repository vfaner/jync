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

    /**
     * SQL Server's toggle is persistent table metadata, and a driver abandons the rest of a
     * multi-statement batch on the first failure. Joined into one script, one bad table would
     * leave the tables after it either unprotected (disable side) or permanently unenforced
     * (re-enable side), long after this connection is gone.
     */
    @Test
    void eachTableGetsItsOwnStatement() {
        assertThat(dialect.getConstraintToggleStatements("dbo", List.of("T1", "T2"), true))
                .containsExactly(
                        "ALTER TABLE [dbo].[T1] NOCHECK CONSTRAINT ALL",
                        "ALTER TABLE [dbo].[T2] NOCHECK CONSTRAINT ALL");
        assertThat(dialect.getConstraintToggleStatements("dbo", List.of("T1", "T2"), false))
                .containsExactly(
                        "ALTER TABLE [dbo].[T1] WITH CHECK CHECK CONSTRAINT ALL",
                        "ALTER TABLE [dbo].[T2] WITH CHECK CHECK CONSTRAINT ALL");
        assertThat(dialect.getConstraintToggleStatements("dbo", List.of(), true)).isEmpty();
    }
}
