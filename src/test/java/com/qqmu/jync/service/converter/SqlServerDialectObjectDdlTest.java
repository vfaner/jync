package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Recreating a view or routine on a SQL Server target.
 *
 * <p>{@code DROP VIEW IF EXISTS} and {@code DROP PROCEDURE IF EXISTS} are SQL Server 2016+
 * syntax; on 2012/2014 the batch fails to parse before the CREATE ever runs, so views and
 * stored procedures never sync there. The {@code OBJECT_ID} guard is valid on every release
 * and needs no version detection.
 */
class SqlServerDialectObjectDdlTest {

    private final SqlServerDialect dialect = new SqlServerDialect();

    @Test
    void aViewDefinitionThatStartsWithCreateIsDroppedThroughAnObjectIdGuard() {
        ViewMeta view = new ViewMeta();
        view.setName("v_sales");
        view.setDefinition("CREATE VIEW v_sales AS SELECT 1 AS one");

        List<String> stmts = dialect.getCreateViewSql(view, "dbo", DatabaseType.ORACLE);

        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0))
                .isEqualTo("IF OBJECT_ID(N'[dbo].[v_sales]', N'V') IS NOT NULL"
                        + " DROP VIEW [dbo].[v_sales]");
        assertThat(stmts.get(1)).isEqualTo("CREATE VIEW v_sales AS SELECT 1 AS one");
    }

    @Test
    void aBareViewBodyIsDroppedThroughAnObjectIdGuard() {
        ViewMeta view = new ViewMeta();
        view.setName("v_sales");
        view.setDefinition("SELECT 1 AS one");

        List<String> stmts = dialect.getCreateViewSql(view, "dbo", DatabaseType.ORACLE);

        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0))
                .isEqualTo("IF OBJECT_ID(N'[dbo].[v_sales]', N'V') IS NOT NULL"
                        + " DROP VIEW [dbo].[v_sales]");
        assertThat(stmts.get(1)).isEqualTo("CREATE VIEW [dbo].[v_sales] AS SELECT 1 AS one");
    }

    @Test
    void aProcedureIsDroppedThroughAnObjectIdGuard() {
        ProcedureMeta procedure = new ProcedureMeta();
        procedure.setName("p_total");
        procedure.setDefinition("CREATE PROCEDURE p_total AS SELECT 1");

        List<String> stmts = dialect.getCreateProcedureSql(procedure, "dbo", DatabaseType.ORACLE);

        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0))
                .isEqualTo("IF OBJECT_ID(N'[dbo].[p_total]') IS NOT NULL"
                        + " DROP PROCEDURE [dbo].[p_total]");
    }

    @Test
    void anObjectNameWithAnApostropheIsEscapedInsideTheObjectIdLiteral() {
        // OBJECT_ID takes a delimited name as a string literal, and T-SQL escapes a quote by
        // doubling it. Without that, a legal bracketed name would end the literal early.
        ViewMeta view = new ViewMeta();
        view.setName("v_it's");
        view.setDefinition("SELECT 1 AS one");

        assertThat(dialect.getCreateViewSql(view, "dbo", DatabaseType.ORACLE).get(0))
                .contains("OBJECT_ID(N'[dbo].[v_it''s]', N'V')");
    }
}
