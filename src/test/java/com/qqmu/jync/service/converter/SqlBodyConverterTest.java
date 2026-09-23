package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * Verifies that body conversion changes only what it understands.
 *
 * <p>The risk here is not a failed conversion — that is visible and recoverable through a DDL
 * override — but a conversion that succeeds and returns different data. These tests pin the
 * cases where a mechanical rewrite would do exactly that.
 */
class SqlBodyConverterTest {

    private final SqlBodyConverter converter = new SqlBodyConverter();

    private String toMysql(String sql) {
        return converter.convert(sql, DatabaseType.ORACLE, DatabaseType.MYSQL);
    }

    // --- String literals and comments are not code ------------------------------------

    /**
     * A whole-word replacement over the raw body edits prose inside string literals. The
     * statement stays valid, so nothing fails; the application just returns different text.
     */
    @Test
    void functionNamesInsideStringLiteralsAreNotRewritten() {
        String sql = "SELECT 'NVL means null value logic' AS note FROM t";
        assertThat(toMysql(sql)).contains("'NVL means null value logic'");
    }

    @Test
    void functionNamesOutsideLiteralsAreStillRewritten() {
        String sql = "SELECT NVL(name, 'unknown') FROM t";
        String out = toMysql(sql);
        assertThat(out).contains("IFNULL(name, 'unknown')");
    }

    @Test
    void bothOccurrencesAreHandledIndependentlyInOneStatement() {
        String sql = "SELECT NVL(a, 'NVL') FROM t";
        String out = toMysql(sql);
        assertThat(out).contains("IFNULL(").contains("'NVL'");
    }

    @Test
    void aQuotedIdentifierSpellingAFunctionNameIsNotRenamed() {
        // A column really named LEN must not become LENGTH, or the statement breaks.
        String sql = "SELECT \"LEN\" FROM t";
        assertThat(toMysql(sql)).contains("LEN");
        assertThat(toMysql(sql)).doesNotContain("LENGTH");
    }

    @Test
    void commentsAreLeftAlone() {
        String sql = "SELECT a FROM t -- NVL is used elsewhere\n";
        assertThat(toMysql(sql)).contains("-- NVL is used elsewhere");
    }

    @Test
    void blockCommentsAreLeftAlone() {
        String sql = "SELECT /* prefer NVL here */ a FROM t";
        assertThat(toMysql(sql)).contains("/* prefer NVL here */");
    }

    @Test
    void anEscapedQuoteInsideALiteralDoesNotEndIt() {
        String sql = "SELECT 'it''s NVL' FROM t";
        assertThat(toMysql(sql)).contains("'it''s NVL'");
    }

    /**
     * Conversion may decline to translate, but it must never drop or duplicate text: an
     * unbalanced quote must leave the remainder intact rather than truncating the body.
     */
    @Test
    void anUnterminatedLiteralPreservesEveryCharacter() {
        String sql = "SELECT 'unterminated FROM t";
        assertThat(toMysql(sql)).hasSameSizeAs(sql);
    }

    // --- Format strings are not translatable by renaming ------------------------------

    /**
     * {@code DATE_FORMAT(d,'YYYY-MM-DD')} is valid MySQL that returns the literal text
     * "YYYY-MM-DD", because MySQL spells those fields %Y-%m-%d. Renaming TO_CHAR therefore
     * produces a wrong answer with no error — worse than leaving it to fail.
     */
    @Test
    void toCharIsNotRenamedToDateFormat() {
        String sql = "SELECT TO_CHAR(created, 'YYYY-MM-DD') FROM t";
        String out = toMysql(sql);
        assertThat(out).doesNotContain("DATE_FORMAT");
        assertThat(out).contains("TO_CHAR");
    }

    @Test
    void toCharOnANumberIsAlsoLeftAlone() {
        // TO_CHAR of a number is not date formatting at all, so no rename could be right.
        String sql = "SELECT TO_CHAR(amount) FROM t";
        assertThat(toMysql(sql)).contains("TO_CHAR");
    }

    // --- Call parentheses follow the target's convention --------------------------------

    /**
     * Products disagree on whether the date/time entry point is a keyword or a function:
     * Oracle's SYSDATE and DB2's CURRENT TIMESTAMP take no parentheses, MySQL's NOW() and
     * SQL Server's GETDATE() require them. Emitting the source's parens around a keyword
     * (SYSDATE()) or omitting them around a function is invalid SQL on the target.
     */
    @Test
    void keywordTargetsDropTheSourceCallParens() {
        assertThat(converter.convert("SELECT NOW() FROM t", DatabaseType.MYSQL, DatabaseType.ORACLE))
                .contains("SYSDATE").doesNotContain("SYSDATE(");
        assertThat(converter.convert("SELECT NOW() FROM t", DatabaseType.MYSQL, DatabaseType.DB2))
                .contains("CURRENT TIMESTAMP").doesNotContain("()");
        // Oracle's bare SYSDATE keyword gains PG-compatible parens via the NOW() value,
        // and must not come out double-called.
        assertThat(converter.convert("SELECT SYSDATE FROM t",
                DatabaseType.ORACLE, DatabaseType.POSTGRESQL))
                .contains("CURRENT_TIMESTAMP").doesNotContain("CURRENT_TIMESTAMP(");
    }

    @Test
    void functionTargetsGainCallParensFromABareKeyword() {
        assertThat(toMysql("SELECT SYSDATE FROM t")).contains("NOW()");
        assertThat(converter.convert("SELECT SYSDATE FROM t",
                DatabaseType.ORACLE, DatabaseType.SQLSERVER))
                .contains("GETDATE()");
    }

    @Test
    void anExpressionValueIsNotCalledTwice() {
        // CURDATE() maps to the expression TRUNC(SYSDATE); the source's empty parens must
        // be consumed rather than appended: TRUNC(SYSDATE)() is invalid.
        assertThat(converter.convert("SELECT CURDATE() FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE))
                .contains("TRUNC(SYSDATE)").doesNotContain("TRUNC(SYSDATE)(");
    }

    @Test
    void argumentListsSurviveTheRename() {
        assertThat(toMysql("SELECT NVL(a, b) FROM t")).contains("IFNULL(a, b)");
        assertThat(converter.convert("SELECT LEN(x) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.ORACLE))
                .contains("LENGTH(x)");
    }

    // --- Conversions that are safe -----------------------------------------------------

    @Test
    void substrBecomesSubstringWithoutMatchingSubstringItself() {
        assertThat(toMysql("SELECT SUBSTR(a, 1, 2) FROM t")).contains("SUBSTRING(a, 1, 2)");
        // Idempotency: converting an already-converted body must not double-apply.
        assertThat(toMysql("SELECT SUBSTRING(a, 1, 2) FROM t")).contains("SUBSTRING(a, 1, 2)");
    }

    @Test
    void fromDualIsRemovedForMysqlAndSwappedForDb2() {
        assertThat(toMysql("SELECT 1 FROM DUAL")).doesNotContainIgnoringCase("DUAL");
        assertThat(converter.convert("SELECT 1 FROM DUAL", DatabaseType.ORACLE, DatabaseType.DB2))
                .containsIgnoringCase("SYSIBM.SYSDUMMY1");
    }

    @Test
    void sameFamilyConversionIsAPassthrough() {
        String sql = "SELECT NVL(a, 'x') FROM DUAL";
        assertThat(converter.convert(sql, DatabaseType.ORACLE, DatabaseType.DM)).isEqualTo(sql);
    }

    @Test
    void nullAndBlankBodiesAreReturnedUnchanged() {
        assertThat(toMysql(null)).isNull();
        assertThat(toMysql("   ")).isEqualTo("   ");
    }

    // --- View body extraction ----------------------------------------------------------

    @Test
    void aViewHeaderIsStrippedLeavingOnlyTheSelect() {
        assertThat(converter.extractViewBody("CREATE OR REPLACE VIEW v AS SELECT 1 FROM t;"))
                .isEqualTo("SELECT 1 FROM t");
        assertThat(converter.extractViewBody("SELECT 1 FROM t")).isEqualTo("SELECT 1 FROM t");
    }
}
