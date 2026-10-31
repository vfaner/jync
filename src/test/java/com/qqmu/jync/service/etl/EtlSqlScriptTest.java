package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The extraction box may hold a script: several statements executed in order on one source
 * connection, the last one supplying the rows. Splitting is textual and conservative — a
 * semicolon inside a string, a quoted or bracketed identifier, or a comment never separates
 * statements — because a wrong split sends half a statement to the server.
 */
class EtlSqlScriptTest {

    @Test
    void aSingleStatementIsReturnedWithoutItsTrailingSemicolon() {
        assertThat(EtlSqlScript.split("SELECT id FROM src;"))
                .containsExactly("SELECT id FROM src");
    }

    @Test
    void statementsAreSplitOnTopLevelSemicolons() {
        assertThat(EtlSqlScript.split(
                "CREATE TEMP TABLE t (id INT); SELECT id FROM t;"))
                .containsExactly("CREATE TEMP TABLE t (id INT)", "SELECT id FROM t");
    }

    @Test
    void semicolonsInsideQuotedIdentifiersAndBracketsDoNotSplit() {
        assertThat(EtlSqlScript.split("SELECT 'a;b', \"c;d\", `e;f`, [g;h] FROM t"))
                .containsExactly("SELECT 'a;b', \"c;d\", `e;f`, [g;h] FROM t");
    }

    @Test
    void aLineCommentHidesItsSemicolonUntilTheNewline() {
        assertThat(EtlSqlScript.split("SELECT 1 -- x;y\n; SELECT 2"))
                .containsExactly("SELECT 1 -- x;y", "SELECT 2");
    }

    @Test
    void aBlockCommentHidesItsSemicolon() {
        assertThat(EtlSqlScript.split("SELECT 1 /* x;y */; SELECT 2"))
                .containsExactly("SELECT 1 /* x;y */", "SELECT 2");
    }

    @Test
    void escapedQuotesDoNotEndTheString() {
        assertThat(EtlSqlScript.split("SELECT 'it''s; fine' FROM t"))
                .containsExactly("SELECT 'it''s; fine' FROM t");
        // MySQL relies on the backslash escape; a ';' after it is still inside the string.
        assertThat(EtlSqlScript.split("SELECT 'a\\';b' FROM t"))
                .containsExactly("SELECT 'a\\';b' FROM t");
    }

    @Test
    void aDollarQuotedBodyKeepsItsSemicolons() {
        assertThat(EtlSqlScript.split("SELECT $$a;b$$ FROM t"))
                .containsExactly("SELECT $$a;b$$ FROM t");
        assertThat(EtlSqlScript.split("SELECT $$one;two$$, $tag$three;four$tag$ FROM t"))
                .containsExactly("SELECT $$one;two$$, $tag$three;four$tag$ FROM t");
    }

    @Test
    void oracleAlternativeQuotingKeepsItsSemicolon() {
        // Oracle's q'[...]' lets a literal carry apostrophes, so it must not end at the first '.
        assertThat(EtlSqlScript.split("SELECT q'[it's; fine]' FROM t"))
                .containsExactly("SELECT q'[it's; fine]' FROM t");
        assertThat(EtlSqlScript.split("SELECT q'{a;b}' FROM t; SELECT 2"))
                .containsExactly("SELECT q'{a;b}' FROM t", "SELECT 2");
    }

    @Test
    void aDollarSignInsideAnIdentifierDoesNotOpenAQuote() {
        // PostgreSQL only starts a dollar quote at a token boundary; a '$' inside a name is
        // part of that name (Oracle and SQL Server allow it too).
        assertThat(EtlSqlScript.split("SELECT a$b$c FROM t; SELECT 2"))
                .containsExactly("SELECT a$b$c FROM t", "SELECT 2");
    }

    @Test
    void emptyAndCommentOnlyStatementsAreDropped() {
        assertThat(EtlSqlScript.split(";; -- setup;\n; SELECT 1; ; /* tail */ ;"))
                .containsExactly("SELECT 1");
    }

    @Test
    void anUnterminatedQuoteOrBlockCommentIsRejected() {
        assertThatThrownBy(() -> EtlSqlScript.split("SELECT 'abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.script.invalid");
        assertThatThrownBy(() -> EtlSqlScript.split("SELECT 1 /* abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.script.invalid");
    }

    @Test
    void validateRejectsAScriptWithoutAnyStatement() {
        assertThatThrownBy(() -> EtlSqlScript.validate("-- nothing to run;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.script.empty");
    }

    @Test
    void validateRejectsAScriptWhoseLastStatementCannotSupplyRows() {
        assertThatThrownBy(() -> EtlSqlScript.validate(
                "CREATE TEMP TABLE t (id INT); COMMIT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.script.lastStatement");
    }

    @Test
    void validateAcceptsSelectAndWithAsTheLastStatement() {
        assertThatCode(() -> EtlSqlScript.validate(
                "CREATE TEMP TABLE t (id INT); -- rows\nSELECT id FROM t"))
                .doesNotThrowAnyException();
        assertThatCode(() -> EtlSqlScript.validate(
                "SET @x = 1; WITH c AS (SELECT 1 AS id) SELECT id FROM c"))
                .doesNotThrowAnyException();
    }

    @Test
    void validateKeepsAcceptingASingleStatementAsBefore() {
        assertThatCode(() -> EtlSqlScript.validate("DELETE FROM t"))
                .doesNotThrowAnyException();
    }
}
