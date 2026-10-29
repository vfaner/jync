package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ProcedureMeta;

/**
 * The SQL*Plus terminator is a {@code /} standing alone on the last line — nothing else.
 *
 * <p>The old stripper removed any trailing slash, so a body whose last token was a block
 * comment lost the comment's closing delimiter and was submitted unterminated: Oracle answers
 * with a PL/SQL compile error and the routine is never created. Definitions do reach this code
 * with trailing comments — the metadata readers hand over the stored source verbatim.
 */
class OracleDialectProcedureTerminatorTest {

    private final OracleDialect dialect = new OracleDialect();

    private static ProcedureMeta procedure(String definition) {
        ProcedureMeta p = new ProcedureMeta();
        p.setName("sync_orders");
        p.setDefinition(definition);
        return p;
    }

    @Test
    void aBodyEndingInABlockCommentKeepsItsClosingDelimiter() {
        String body = "CREATE OR REPLACE PROCEDURE sync_orders AS\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;\n"
                + "/* migrated from MySQL */";

        assertThat(dialect.getCreateProcedureSql(procedure(body), "HR", null))
                .containsExactly(body);
    }

    @Test
    void aTerminatorOnItsOwnLineIsStillRemoved() {
        String sql = "CREATE OR REPLACE PROCEDURE sync_orders AS\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;\n"
                + "/";

        assertThat(dialect.getCreateProcedureSql(procedure(sql), "HR", null))
                .containsExactly("CREATE OR REPLACE PROCEDURE sync_orders AS\n"
                        + "BEGIN\n"
                        + "  NULL;\n"
                        + "END;");
    }
}
