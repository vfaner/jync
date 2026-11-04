package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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

    /** Wired like production: CAST/CONVERT type names go through the real dialect mapping. */
    private final SqlBodyConverter converter = wiredConverter();

    private static SqlBodyConverter wiredConverter() {
        List<SqlDialect> dialects = List.of(
                new GenericSqlDialect(), new MySqlDialect(), new OracleDialect(),
                new PostgresDialect(), new SqlServerDialect(), new Db2Dialect(),
                new HanaSqlDialect());
        SqlDialectFactory factory =
                new SqlDialectFactory(dialects, new GenericSqlDialect());
        return new SqlBodyConverter(new RoutineTypeMapper(), factory);
    }

    private String toMysql(String sql) {
        return conv(sql, DatabaseType.ORACLE, DatabaseType.MYSQL);
    }

    /** String-only view of a conversion; for blocking checks use converter.convert directly. */
    private String conv(String sql, DatabaseType from, DatabaseType to) {
        return converter.convert(sql, from, to).sql();
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
        assertThat(conv("SELECT NOW() FROM t", DatabaseType.MYSQL, DatabaseType.ORACLE))
                .contains("SYSDATE").doesNotContain("SYSDATE(");
        assertThat(conv("SELECT NOW() FROM t", DatabaseType.MYSQL, DatabaseType.DB2))
                .contains("CURRENT TIMESTAMP").doesNotContain("()");
        // Oracle's bare SYSDATE keyword gains PG-compatible parens via the NOW() value,
        // and must not come out double-called.
        assertThat(conv("SELECT SYSDATE FROM t",
                DatabaseType.ORACLE, DatabaseType.POSTGRESQL))
                .contains("CURRENT_TIMESTAMP").doesNotContain("CURRENT_TIMESTAMP(");
    }

    @Test
    void functionTargetsGainCallParensFromABareKeyword() {
        assertThat(toMysql("SELECT SYSDATE FROM t")).contains("NOW()");
        assertThat(conv("SELECT SYSDATE FROM t",
                DatabaseType.ORACLE, DatabaseType.SQLSERVER))
                .contains("GETDATE()");
    }

    @Test
    void anExpressionValueIsNotCalledTwice() {
        // CURDATE() maps to the expression TRUNC(SYSDATE); the source's empty parens must
        // be consumed rather than appended: TRUNC(SYSDATE)() is invalid.
        assertThat(conv("SELECT CURDATE() FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE))
                .contains("TRUNC(SYSDATE)").doesNotContain("TRUNC(SYSDATE)(");
    }

    @Test
    void argumentListsSurviveTheRename() {
        assertThat(toMysql("SELECT NVL(a, b) FROM t")).contains("IFNULL(a, b)");
        assertThat(conv("SELECT LEN(x) FROM t",
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
        assertThat(conv("SELECT 1 FROM DUAL", DatabaseType.ORACLE, DatabaseType.DB2))
                .containsIgnoringCase("SYSIBM.SYSDUMMY1");
    }

    @Test
    void sameFamilyConversionIsAPassthrough() {
        String sql = "SELECT NVL(a, 'x') FROM DUAL";
        assertThat(conv(sql, DatabaseType.ORACLE, DatabaseType.DM)).isEqualTo(sql);
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

    // --- NOW(fsp): the precision argument must not dangle after a rename ---------------

    /**
     * MySQL {@code NOW(6)} renamed with the plain "NOW → SYSDATE" rule would become
     * {@code SYSDATE(6)}, which Oracle rejects. Precision time maps to SYSTIMESTAMP.
     */
    @Test
    void nowWithPrecisionBecomesSystimestampOnOracle() {
        String out = conv("SELECT NOW(6) FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE);
        assertThat(out).contains("SYSTIMESTAMP");
        assertThat(out).doesNotContain("SYSDATE(6)", "SYSTIMESTAMP(", "NOW(6)");
    }

    /** SQL Server cannot take the argument either: GETDATE()(6) would be emitted otherwise. */
    @Test
    void nowWithPrecisionBecomesSysdatetimeOnSqlServer() {
        String out = conv("SELECT NOW(3) FROM t",
                DatabaseType.MYSQL, DatabaseType.SQLSERVER);
        assertThat(out).contains("SYSDATETIME()");
        assertThat(out).doesNotContain("GETDATE()(3)", "NOW(3)");
    }

    /** MySQL/PostgreSQL NOW(fsp) is native and stays; Oracle SYSTIMESTAMP to MySQL keeps ms. */
    @Test
    void nowPrecisionIsNativeOnMysqlAndPostgresAndSystimestampKeepsIt() {
        assertThat(conv("SELECT NOW(6) FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.MYSQL)).contains("NOW(6)");
        assertThat(toMysql("SELECT SYSTIMESTAMP FROM t")).contains("NOW(6)");
    }

    // --- LIMIT / FROM DUAL rewrites skip literals --------------------------------------

    /** A view body ending in a literal containing "LIMIT 5" must not gain a FETCH FIRST. */
    @Test
    void limitInsideALiteralIsNotRewritten() {
        String sql = "SELECT 'x LIMIT 5' AS note FROM t";
        String out = conv(sql, DatabaseType.MYSQL, DatabaseType.ORACLE);
        assertThat(out).isEqualTo(sql);
        assertThat(out).doesNotContain("FETCH FIRST");
    }

    /**
     * MySQL accepts both "LIMIT n OFFSET m" and "LIMIT m, n" (offset first). Only the bare
     * "LIMIT n" form was rewritten; either offset form went through verbatim and Oracle/DB2
     * answered ORA-00933 / SQL0104N, so the view failed on every cycle and never retired.
     */
    @Test
    void limitWithOffsetIsRewrittenForOracleAndDb2() {
        assertThat(converter.convertLimitClause("SELECT * FROM t LIMIT 10 OFFSET 5",
                DatabaseType.DialectFamily.MYSQL, DatabaseType.DialectFamily.ORACLE))
                .isEqualTo("SELECT * FROM t OFFSET 5 ROWS FETCH FIRST 10 ROWS ONLY");
        assertThat(converter.convertLimitClause("SELECT * FROM t LIMIT 5, 10",
                DatabaseType.DialectFamily.MYSQL, DatabaseType.DialectFamily.DB2))
                .isEqualTo("SELECT * FROM t OFFSET 5 ROWS FETCH FIRST 10 ROWS ONLY");
    }

    /**
     * The old rule was anchored at end-of-string, so a LIMIT inside a derived table (or before
     * a trailing semicolon) slipped through and Oracle/DB2 rejected the whole view.
     */
    @Test
    void limitInsideASubqueryOrBeforeASemicolonIsAlsoRewritten() {
        assertThat(conv("SELECT * FROM (SELECT id FROM s ORDER BY id LIMIT 5) x",
                DatabaseType.MYSQL, DatabaseType.ORACLE))
                .isEqualTo("SELECT * FROM (SELECT id FROM s ORDER BY id"
                        + " FETCH FIRST 5 ROWS ONLY) x");
        assertThat(conv("SELECT * FROM t LIMIT 10 OFFSET 5;",
                DatabaseType.MYSQL, DatabaseType.ORACLE))
                .isEqualTo("SELECT * FROM t OFFSET 5 ROWS FETCH FIRST 10 ROWS ONLY;");
        assertThat(conv("SELECT * FROM (SELECT * FROM s LIMIT 5, 10) x",
                DatabaseType.MYSQL, DatabaseType.DB2))
                .isEqualTo("SELECT * FROM (SELECT * FROM s"
                        + " OFFSET 5 ROWS FETCH FIRST 10 ROWS ONLY) x");
    }

    /**
     * Reverse direction: the standards families' FETCH FIRST / OFFSET ... FETCH is the same
     * row-limiting semantics as MySQL LIMIT. SQL Server spells it FETCH NEXT, and Oracle/DB2
     * allow ROW or ROWS.
     */
    @Test
    void fetchFirstAndOffsetFetchBecomeLimitOnAMysqlTarget() {
        assertThat(conv("SELECT * FROM t FETCH FIRST 10 ROWS ONLY",
                DatabaseType.ORACLE, DatabaseType.MYSQL))
                .isEqualTo("SELECT * FROM t LIMIT 10");
        assertThat(conv("SELECT * FROM t OFFSET 5 ROWS FETCH FIRST 10 ROWS ONLY",
                DatabaseType.ORACLE, DatabaseType.MYSQL))
                .isEqualTo("SELECT * FROM t LIMIT 10 OFFSET 5");
        assertThat(conv("SELECT * FROM t OFFSET 5 ROW FETCH NEXT 10 ROWS ONLY",
                DatabaseType.SQLSERVER, DatabaseType.MYSQL))
                .isEqualTo("SELECT * FROM t LIMIT 10 OFFSET 5");
        // Subqueries and trailing semicolons on this direction too.
        assertThat(conv("SELECT * FROM (SELECT id FROM s FETCH FIRST 5 ROWS ONLY) x",
                DatabaseType.POSTGRESQL, DatabaseType.MYSQL))
                .isEqualTo("SELECT * FROM (SELECT id FROM s LIMIT 5) x");
    }

    /** PostgreSQL speaks FETCH FIRST natively, so no rewrite is needed in that direction. */
    @Test
    void fetchFirstStaysUntouchedOnFamiliesThatSpeakIt() {
        String sql = "SELECT * FROM t OFFSET 5 ROWS FETCH FIRST 10 ROWS ONLY";
        assertThat(conv(sql, DatabaseType.ORACLE, DatabaseType.POSTGRESQL)).isEqualTo(sql);
        assertThat(conv(sql, DatabaseType.ORACLE, DatabaseType.DB2)).isEqualTo(sql);
        assertThat(conv(sql, DatabaseType.ORACLE, DatabaseType.HANA)).isEqualTo(sql);
        // A complete OFFSET ... FETCH is native T-SQL as well.
        assertThat(conv(sql, DatabaseType.ORACLE, DatabaseType.SQLSERVER)).isEqualTo(sql);
    }

    /**
     * A bare FETCH FIRST without OFFSET is not valid T-SQL (it demands ORDER BY ... OFFSET),
     * so it cannot be passed through to SQL Server; block and name the alternatives instead
     * of silently widening or guessing an ORDER BY.
     */
    @Test
    void bareFetchFirstToSqlServerIsBlocked() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT * FROM t FETCH FIRST 10 ROWS ONLY",
                DatabaseType.ORACLE, DatabaseType.SQLSERVER);
        assertThat(result.blockingReasons()).hasSize(1);
        assertThat(result.blockingReasons().get(0))
                .contains("FETCH FIRST").contains("ORDER BY").contains("TOP");
        assertThat(result.sql()).contains("FETCH FIRST 10 ROWS ONLY");
    }

    /** FETCH FIRST inside a literal is neither rewritten nor blocked. */
    @Test
    void fetchFirstInsideALiteralIsNotRewritten() {
        String sql = "SELECT 'FETCH FIRST 10 ROWS ONLY' AS note FROM t";
        assertThat(conv(sql, DatabaseType.ORACLE, DatabaseType.MYSQL)).isEqualTo(sql);
        assertThat(converter.convert(sql, DatabaseType.ORACLE, DatabaseType.SQLSERVER)
                .blockingReasons()).isEmpty();
    }

    /** Removing a real FROM DUAL must leave a literal that merely mentions it intact. */
    @Test
    void fromDualInsideALiteralIsNotRemoved() {
        String out = toMysql("SELECT 'x FROM DUAL y' AS note FROM DUAL");
        assertThat(out).contains("'x FROM DUAL y'");
        // The real trailing FROM DUAL is gone; only the literal's mention remains.
        assertThat(out).endsWith("AS note");
    }

    // --- SAP HANA: DUMMY in, DUMMY out -----------------------------------------------

    /**
     * HANA's dummy table is DUMMY. Oracle spells it DUAL, MySQL accepts DUAL, DB2 has
     * SYSIBM.SYSDUMMY1. PostgreSQL and SQL Server have none, so the clause goes — the row
     * still evaluates there, as it does for the Oracle→PostgreSQL path above.
     */
    @Test
    void hanaDummyIsTranslatedForEachTarget() {
        assertThat(conv("SELECT 1 FROM DUMMY", DatabaseType.HANA,
                DatabaseType.ORACLE)).isEqualTo("SELECT 1 FROM DUAL");
        assertThat(conv("SELECT 1 FROM DUMMY", DatabaseType.HANA,
                DatabaseType.MYSQL)).isEqualTo("SELECT 1 FROM DUAL");
        assertThat(conv("SELECT 1 FROM DUMMY", DatabaseType.HANA,
                DatabaseType.DB2)).contains("SYSIBM.SYSDUMMY1");
        assertThat(conv("SELECT 1 FROM DUMMY", DatabaseType.HANA,
                DatabaseType.POSTGRESQL)).isEqualTo("SELECT 1");
    }

    /** The dummy table carries a WHERE clause in real bodies; it must survive the rewrite. */
    @Test
    void aFilteredDummyQueryKeepsItsFilter() {
        assertThat(conv("SELECT a FROM DUMMY WHERE 1 = 1",
                DatabaseType.HANA, DatabaseType.ORACLE))
                .isEqualTo("SELECT a FROM DUAL WHERE 1 = 1");
    }

    /** HANA rejects a SELECT with no FROM (syntax error), so a bare one gains FROM DUMMY. */
    @Test
    void aBareSelectGetsFromDummyWhenTheTargetIsHana() {
        assertThat(conv("SELECT 1", DatabaseType.MYSQL, DatabaseType.HANA))
                .isEqualTo("SELECT 1 FROM DUMMY");
    }

    @Test
    void anOracleDualBecomesDummyForHana() {
        assertThat(conv("SELECT 1 FROM DUAL", DatabaseType.ORACLE,
                DatabaseType.HANA)).isEqualTo("SELECT 1 FROM DUMMY");
        assertThat(conv("SELECT a FROM DUAL WHERE 1 = 1", DatabaseType.ORACLE,
                DatabaseType.HANA)).isEqualTo("SELECT a FROM DUMMY WHERE 1 = 1");
    }

    @Test
    void functionsAreRewrittenForHana() {
        assertThat(conv("SELECT IFNULL(a, 0)", DatabaseType.MYSQL,
                DatabaseType.HANA)).contains("COALESCE(a, 0)");
        assertThat(conv("SELECT NOW()", DatabaseType.ORACLE,
                DatabaseType.HANA)).contains("CURRENT_TIMESTAMP");
    }

    /**
     * A bare SELECT whose literal mentions FROM must still gain FROM DUAL for Oracle; the old
     * blind regex saw the literal's FROM and skipped it, producing invalid Oracle SQL.
     */
    @Test
    void bareSelectWithFromInsideALiteralStillGainsDual() {
        String out = conv("SELECT 'FROM X' AS note",
                DatabaseType.MYSQL, DatabaseType.ORACLE);
        assertThat(out).contains("FROM DUAL");
        assertThat(out).contains("'FROM X'");
    }

    // --- Detecting '||' as code, not as text ------------------------------------------

    /**
     * Oracle joins strings with {@code ||}. A MySQL-family target reads the same token as
     * logical OR, so the object is created successfully but returns 0/1 — a silent wrong
     * answer. The page warns only when the operator can still act on it.
     */
    @Test
    void unquotedPipesAreStringConcatenation() {
        assertThat(SqlBodyConverter.containsStringConcatenation(
                "SELECT first_name || ' ' || last_name FROM users")).isTrue();
        assertThat(SqlBodyConverter.containsStringConcatenation(
                "SELECT a||b FROM t")).isTrue();
    }

    @Test
    void pipesInsideAStringLiteralAreJustText() {
        assertThat(SqlBodyConverter.containsStringConcatenation(
                "SELECT 'a||b' AS note FROM t")).isFalse();
        // A doubled quote keeps the rest of the literal inside it.
        assertThat(SqlBodyConverter.containsStringConcatenation(
                "SELECT 'it''s a||b' AS note FROM t")).isFalse();
    }

    @Test
    void pipesInsideADoubleQuotedIdentifierAreNotCode() {
        assertThat(SqlBodyConverter.containsStringConcatenation(
                "SELECT \"a||b\" FROM t")).isFalse();
    }

    @Test
    void commentedOutPipesAreNotCode() {
        assertThat(SqlBodyConverter.containsStringConcatenation(
                "SELECT a, b -- c || d\nFROM t")).isFalse();
        assertThat(SqlBodyConverter.containsStringConcatenation(
                "SELECT a, /* c || d */ b FROM t")).isFalse();
    }

    @Test
    void aSinglePipeIsNotConcatenation() {
        assertThat(SqlBodyConverter.containsStringConcatenation(
                "SELECT a | b FROM t")).isFalse();
        assertThat(SqlBodyConverter.containsStringConcatenation(null)).isFalse();
        assertThat(SqlBodyConverter.containsStringConcatenation("  ")).isFalse();
    }

    @Test
    void anUnterminatedLiteralSwallowsTheRest() {
        assertThat(SqlBodyConverter.containsStringConcatenation(
                "SELECT 'a || b FROM t")).isFalse();
    }

    // --- Blocking: conversions that would silently change semantics --------------------

    /**
     * Oracle/PostgreSQL join strings with {@code ||}; MySQL reads the same token as logical OR
     * and the view would return 0/1 with no error. Conversion must refuse rather than emit an
     * object with wrong semantics, naming the fix (CONCAT or a DDL override).
     */
    @Test
    void pipeConcatenationToMysqlIsBlockedInsteadOfBecomingLogicalOr() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT first_name || last_name FROM users",
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons()).hasSize(1);
        assertThat(result.blockingReasons().get(0))
                .contains("||").containsIgnoringCase("CONCAT");
    }

    @Test
    void pipeConcatenationToPostgresIsNotBlocked() {
        assertThat(converter.convert("SELECT a || b FROM t",
                DatabaseType.ORACLE, DatabaseType.POSTGRESQL).blocked()).isFalse();
    }

    @Test
    void pipesOnlyInsideALiteralDoNotBlock() {
        assertThat(converter.convert("SELECT 'a||b' AS note FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL).blocked()).isFalse();
    }

    /**
     * SQL Server has no LIMIT. Stripping it silently turns "first n rows" into the whole result
     * set; block instead. The LIMIT inside a subquery is just as invalid, so both are caught.
     */
    @Test
    void limitToSqlServerIsBlockedAtAnyQueryLevel() {
        assertThat(converter.convert("SELECT * FROM t LIMIT 10",
                DatabaseType.MYSQL, DatabaseType.SQLSERVER).blocked()).isTrue();
        assertThat(converter.convert(
                "SELECT * FROM t WHERE id IN (SELECT id FROM s LIMIT 5)",
                DatabaseType.MYSQL, DatabaseType.SQLSERVER).blocked()).isTrue();
    }

    /** The preview UI still needs the unconverted text; blocking must not strip the LIMIT. */
    @Test
    void limitToSqlServerKeepsTheTextForPreview() {
        SqlBodyConverter.ConversionResult result = converter.convert("SELECT * FROM t LIMIT 10",
                DatabaseType.MYSQL, DatabaseType.SQLSERVER);
        assertThat(result.sql()).contains("LIMIT 10");
    }

    @Test
    void limitMentionedInALiteralDoesNotBlock() {
        assertThat(converter.convert("SELECT 'x LIMIT 10' AS note FROM t",
                DatabaseType.MYSQL, DatabaseType.SQLSERVER).blocked()).isFalse();
    }

    // --- Set difference: MINUS vs EXCEPT --------------------------------------------

    @Test
    void oracleMinusBecomesExceptForTheStandardsFamilies() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT id FROM a MINUS SELECT id FROM b",
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(result.sql()).contains("EXCEPT").doesNotContain("MINUS");
        assertThat(result.blocked()).isFalse();
    }

    @Test
    void exceptBecomesMinusOnAnOracleTarget() {
        assertThat(conv("SELECT id FROM a EXCEPT SELECT id FROM b",
                DatabaseType.POSTGRESQL, DatabaseType.ORACLE))
                .contains("MINUS").doesNotContain("EXCEPT");
    }

    @Test
    void minusStaysMinusOnAHanaTargetWhichAcceptsBoth() {
        assertThat(conv("SELECT id FROM a MINUS SELECT id FROM b",
                DatabaseType.ORACLE, DatabaseType.HANA))
                .contains("MINUS").doesNotContain("EXCEPT");
    }

    @Test
    void exceptAllToOracleIsBlockedBecauseMinusIsAlwaysDistinct() {
        // EXCEPT ALL keeps duplicates; MINUS (Oracle's only spelling before 23c) removes them.
        // A rename would silently change the result, so it must be refused.
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT id FROM a EXCEPT ALL SELECT id FROM b",
                DatabaseType.POSTGRESQL, DatabaseType.ORACLE);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0))
                .containsIgnoringCase("EXCEPT ALL");
    }

    @Test
    void theWordMinusInsideALiteralIsNotRewritten() {
        assertThat(conv("SELECT 'a minus b' AS note FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL))
                .contains("'a minus b'").doesNotContain("EXCEPT");
    }

    // --- LEN -> MySQL must count characters, not bytes -------------------------------

    /**
     * SQL Server LEN counts characters and ignores trailing spaces. The old MySQL target rule
     * renamed it to LENGTH, which counts bytes: LEN('你好') = 2 became LENGTH('你好') = 6, and
     * LEN('a ') = 1 became LENGTH('a ') = 2. CHAR_LENGTH has the SQL Server semantics.
     */
    @Test
    void lenToMysqlBecomesCharLengthNotByteLength() {
        String out = conv("SELECT LEN(name) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.MYSQL);
        assertThat(out).contains("CHAR_LENGTH(name)");
        // Strip the intended spelling before checking that no byte-length LENGTH( remains.
        assertThat(out.replace("CHAR_LENGTH", "")).doesNotContain("LENGTH(");
    }

    // --- MySQL one-argument ISNULL is a predicate, not a null-coalescing call ---------

    /**
     * MySQL's ISNULL(x) is a one-argument predicate (1/0), unlike SQL Server's two-argument
     * ISNULL(a,b). Renaming it with the coalesce maps produced COALESCE(x) on PostgreSQL —
     * legal, but NULL when x is NULL, the opposite of the ISNULL TRUE result. It must become
     * the predicate "x IS NULL" before the function-name maps run.
     */
    @Test
    void mysqlOneArgIsnullBecomesAnIsNullPredicateOnEveryOtherFamily() {
        String sql = "SELECT * FROM t WHERE ISNULL(name)";
        assertThat(conv(sql, DatabaseType.MYSQL, DatabaseType.POSTGRESQL))
                .contains("name IS NULL").doesNotContain("COALESCE(name)");
        assertThat(conv(sql, DatabaseType.MYSQL, DatabaseType.ORACLE))
                .contains("name IS NULL").doesNotContain("NVL(name)");
        assertThat(conv(sql, DatabaseType.MYSQL, DatabaseType.DB2))
                .contains("name IS NULL").doesNotContain("COALESCE(name)");
        assertThat(conv(sql, DatabaseType.MYSQL, DatabaseType.HANA))
                .contains("name IS NULL").doesNotContain("COALESCE(name)");
        // SQL Server's ISNULL requires two arguments, so the one-argument call fails there too.
        assertThat(conv(sql, DatabaseType.MYSQL, DatabaseType.SQLSERVER))
                .contains("name IS NULL");
    }

    @Test
    void mysqlIsnullWithAComplexArgumentKeepsTheWholeArgument() {
        String out = conv("SELECT * FROM t WHERE ISNULL(CONCAT(a, b))",
                DatabaseType.MYSQL, DatabaseType.POSTGRESQL);
        assertThat(out).contains("CONCAT(a, b) IS NULL");
    }

    /** The word ISNULL inside a literal is, as always, not code and must stay put. */
    @Test
    void isnullInsideALiteralIsNotTurnedIntoAPredicate() {
        String sql = "SELECT 'ISNULL(x)' AS note FROM t";
        assertThat(conv(sql, DatabaseType.MYSQL, DatabaseType.POSTGRESQL)).isEqualTo(sql);
    }

    // --- S1: Oracle-style date arithmetic must not become numeric subtraction --------

    /**
     * SYSDATE is renamed to NOW() for a MySQL target, so SYSDATE-1 becomes NOW()-1. MySQL
     * coerces NOW() to the number 20261104123000 and subtracts one: the view creates fine and
     * returns a number, not yesterday. Conversion must block and name DATE_ADD/INTERVAL.
     */
    @Test
    void dateArithmeticAroundTheClockFunctionIsBlocked() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT SYSDATE - 1 AS yesterday FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0))
                .containsIgnoringCase("date arithmetic").contains("DATE_ADD");
    }

    @Test
    void fractionalDayDateArithmeticIsAlsoBlocked() {
        // SYSDATE - 1/24 is "one hour ago" in Oracle; MySQL would evaluate it numerically.
        assertThat(converter.convert("SELECT SYSDATE - 1/24 FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL).blocked()).isTrue();
    }

    /** MySQL's own interval arithmetic is the target spelling and must not be blocked. */
    @Test
    void numericSubtractionUsingIntervalIsNotDateArithmetic() {
        String sql = "SELECT NOW() - INTERVAL 1 DAY AS yesterday FROM t";
        assertThat(converter.convert(sql, DatabaseType.MYSQL,
                DatabaseType.POSTGRESQL).blocked()).isFalse();
    }

    /** Date-arithmetic text inside a literal does not count. */
    @Test
    void dateArithmeticInsideALiteralIsNotBlocked() {
        assertThat(converter.convert("SELECT 'SYSDATE - 1' AS note FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL).blocked()).isFalse();
    }

    // --- S2: MySQL backslash escapes must not silently change literal values ---------

    /**
     * MySQL reads '\n' inside a literal as a newline and '\\' as one backslash; the other five
     * products treat '\' as a plain character, so the same text stores different values with
     * no error. Block and name the alternatives (doubled quotes / CHR()).
     */
    @Test
    void aMysqlLiteralWithABackslashEscapeIsBlocked() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT 'C:\\new\\test' AS p FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0))
                .containsIgnoringCase("backslash");
    }

    @Test
    void aBacktickOrDoubleQuotedIdentifierWithABackslashIsNotBlocked() {
        assertThat(converter.convert("SELECT `a\\b` FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE).blocked()).isFalse();
    }

    @Test
    void aLiteralWithoutBackslashesFromMysqlIsFine() {
        assertThat(converter.convert("SELECT 'plain text' FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE).blocked()).isFalse();
    }

    // --- B1: ROWNUM has no meaning outside the Oracle/HANA/DB2 families --------------

    @Test
    void rownumIsBlockedForTheFamiliesThatLackIt() {
        String sql = "SELECT * FROM t WHERE ROWNUM <= 10";
        SqlBodyConverter.ConversionResult mysql = converter.convert(
                sql, DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(mysql.blocked()).isTrue();
        assertThat(mysql.blockingReasons().get(0)).contains("ROWNUM").contains("LIMIT");
        assertThat(converter.convert(sql, DatabaseType.ORACLE,
                DatabaseType.POSTGRESQL).blocked()).isTrue();
        assertThat(converter.convert(sql, DatabaseType.ORACLE,
                DatabaseType.SQLSERVER).blocked()).isTrue();
    }

    @Test
    void rownumIsNotBlockedForHanaAndDb2WhichHaveIt() {
        String sql = "SELECT * FROM t WHERE ROWNUM <= 10";
        assertThat(converter.convert(sql, DatabaseType.ORACLE,
                DatabaseType.HANA).blocked()).isFalse();
        assertThat(converter.convert(sql, DatabaseType.ORACLE,
                DatabaseType.DB2).blocked()).isFalse();
    }

    // --- B2: TOP PERCENT / WITH TIES cannot be expressed with LIMIT -----------------

    @Test
    void topPercentAndWithTiesAreBlockedForEveryOtherFamily() {
        SqlBodyConverter.ConversionResult percent = converter.convert(
                "SELECT TOP 10 PERCENT * FROM t",
                DatabaseType.SQLSERVER, DatabaseType.MYSQL);
        assertThat(percent.blocked()).isTrue();
        assertThat(percent.blockingReasons().get(0)).containsIgnoringCase("PERCENT");
        SqlBodyConverter.ConversionResult ties = converter.convert(
                "SELECT TOP 10 * FROM t WITH TIES",
                DatabaseType.SQLSERVER, DatabaseType.POSTGRESQL);
        assertThat(ties.blocked()).isTrue();
        assertThat(ties.blockingReasons().get(0)).containsIgnoringCase("TIES");
    }

    // --- B3: Oracle outer-join marker (+) has no textual equivalent ------------------

    @Test
    void oracleOldStyleOuterJoinMarkerIsBlocked() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT a.id, b.name FROM a, b WHERE a.id = b.id(+)",
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0))
                .contains("(+)").containsIgnoringCase("OUTER JOIN");
    }

    // --- Safe, same-semantics function renames ---------------------------------------

    @Test
    void ceilingAndCeilAreSwappedForTheFamilyThatUsesEachSpelling() {
        assertThat(conv("SELECT CEILING(x) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.ORACLE)).contains("CEIL(x)");
        assertThat(conv("SELECT CEIL(x) FROM t",
                DatabaseType.ORACLE, DatabaseType.SQLSERVER)).contains("CEILING(x)");
    }

    @Test
    void replicateAndRepeatAreSwapped() {
        assertThat(conv("SELECT REPLICATE(s, n) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.MYSQL)).contains("REPEAT(s, n)");
        assertThat(conv("SELECT REPEAT(s, n) FROM t",
                DatabaseType.MYSQL, DatabaseType.SQLSERVER)).contains("REPLICATE(s, n)");
    }

    @Test
    void randomAndRandAreSwappedOnlyForTheEmptyCall() {
        assertThat(conv("SELECT RANDOM() FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.MYSQL)).contains("RAND()");
        assertThat(conv("SELECT RAND() FROM t",
                DatabaseType.MYSQL, DatabaseType.POSTGRESQL)).contains("RANDOM()");
        // A seeded RAND(seed) cannot become RANDOM(seed): PostgreSQL rejects it, so leave the
        // call intact for the target to reject loudly instead of emitting a false rename.
        assertThat(conv("SELECT RAND(5) FROM t",
                DatabaseType.MYSQL, DatabaseType.POSTGRESQL)).doesNotContain("RANDOM");
    }

    @Test
    void eomonthAndLastDayAreSwapped() {
        assertThat(conv("SELECT EOMONTH(d) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.ORACLE)).contains("LAST_DAY(d)");
        assertThat(conv("SELECT LAST_DAY(d) FROM t",
                DatabaseType.ORACLE, DatabaseType.SQLSERVER)).contains("EOMONTH(d)");
    }

    @Test
    void charindexAndLocateAreSwappedWithTheSameArgumentOrder() {
        assertThat(conv("SELECT CHARINDEX(sub, str, 3) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.MYSQL)).contains("LOCATE(sub, str, 3)");
        assertThat(conv("SELECT LOCATE(sub, str) FROM t",
                DatabaseType.MYSQL, DatabaseType.SQLSERVER)).contains("CHARINDEX(sub, str)");
    }

    @Test
    void strposBecomesInstrOrPosstr() {
        assertThat(conv("SELECT STRPOS(str, sub) FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.MYSQL)).contains("INSTR(str, sub)");
        assertThat(conv("SELECT STRPOS(str, sub) FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.DB2)).contains("POSSTR(str, sub)");
    }

    @Test
    void powBecomesPower() {
        assertThat(conv("SELECT POW(x, 2) FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE)).contains("POWER(x, 2)");
    }

    @Test
    void ucaseAndLcaseBecomeUpperAndLower() {
        assertThat(conv("SELECT UCASE(x) FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE)).contains("UPPER(x)");
        assertThat(conv("SELECT LCASE(x) FROM t",
                DatabaseType.MYSQL, DatabaseType.SQLSERVER)).contains("LOWER(x)");
    }

    @Test
    void datalengthMapsToTheByteLengthFunctionOfTheTarget() {
        assertThat(conv("SELECT DATALENGTH(x) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.POSTGRESQL))
                .contains("OCTET_LENGTH(x)");
        assertThat(conv("SELECT DATALENGTH(x) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.ORACLE))
                .contains("LENGTHB(x)");
        assertThat(conv("SELECT DATALENGTH(x) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.MYSQL))
                .contains("LENGTH(x)");
    }

    @Test
    void curdateBecomesTheParenlessCurrentDateKeywordOnPostgres() {
        assertThat(conv("SELECT CURDATE() FROM t",
                DatabaseType.MYSQL, DatabaseType.POSTGRESQL))
                .contains("CURRENT_DATE").doesNotContain("CURRENT_DATE(");
    }

    // --- DECODE / NVL2 -> searched CASE ----------------------------------------------

    /**
     * Oracle DECODE(expr, search, result, ...) is a searched match. It becomes the standard
     * CASE expr WHEN search THEN result ... END; literals inside the arguments are preserved.
     */
    @Test
    void decodeBecomesASearchedCaseExpression() {
        String out = conv("SELECT DECODE(status, 'Y', 1, 'N', 0) FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(out).contains("CASE status WHEN 'Y' THEN 1 WHEN 'N' THEN 0 END");
        assertThat(out).doesNotContain("DECODE");
    }

    @Test
    void decodeWithATrailingDefaultGainsAnElseClause() {
        String out = conv("SELECT DECODE(x, 1, 'a', 2, 'b', 'c') FROM t",
                DatabaseType.ORACLE, DatabaseType.POSTGRESQL);
        assertThat(out).contains("CASE x WHEN 1 THEN 'a' WHEN 2 THEN 'b' ELSE 'c' END");
    }

    /**
     * DECODE matches a NULL search against a NULL expression (it uses IS-equality), but
     * "CASE expr WHEN NULL" never matches. A rename would silently break the NULL case, so the
     * object must be blocked instead.
     */
    @Test
    void decodeSearchingForNullIsBlocked() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT DECODE(x, NULL, 1, 0) FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0))
                .contains("DECODE").containsIgnoringCase("NULL");
    }

    /** NVL2(expr, if_not_null, if_null) becomes a CASE with an explicit NULL test. */
    @Test
    void nvl2BecomesACaseWithAnIsNullTest() {
        String out = conv("SELECT NVL2(name, name, 'none') FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(out).contains("CASE WHEN name IS NOT NULL THEN name ELSE 'none' END");
        assertThat(out).doesNotContain("NVL2");
    }

    // --- B4: SQL Server / MySQL CONVERT(...) becomes CAST, or blocks -------------------

    @Test
    void sqlServerTwoArgConvertBecomesCastWithTargetCastType() {
        // SQL Server orders (type, expr); MySQL's CAST cannot name VARCHAR, only CHAR.
        String out = conv("SELECT CONVERT(VARCHAR(10), name) FROM t",
                DatabaseType.SQLSERVER, DatabaseType.MYSQL);
        assertThat(out).isEqualTo("SELECT CAST(name AS CHAR(10)) FROM t");
    }

    @Test
    void mysqlConvertBecomesCastWithSwappedArguments() {
        // Oracle keeps its char-length semantics suffix (CHAR), which is legal inside CAST.
        String out = conv("SELECT CONVERT(name, CHAR(10)) FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE);
        assertThat(out).startsWith("SELECT CAST(name AS CHAR(10").endsWith(") FROM t");
        assertThat(out).doesNotContain("CONVERT");
    }

    /** A style argument drives a date format string; CAST has no place for it, so block. */
    @Test
    void sqlServerConvertWithStyleIsBlockedAndKeptForPreview() {
        String sql = "SELECT CONVERT(VARCHAR(10), hiredate, 112) FROM t";
        SqlBodyConverter.ConversionResult result = converter.convert(sql,
                DatabaseType.SQLSERVER, DatabaseType.MYSQL);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0))
                .containsIgnoringCase("CONVERT").contains("style");
        assertThat(result.sql()).contains(sql);
    }

    /** CONVERT(x USING charset) is a character-set conversion, not a type cast. */
    @Test
    void convertUsingCharsetIsBlocked() {
        SqlBodyConverter.ConversionResult mysql = converter.convert(
                "SELECT CONVERT(name USING utf8mb4) FROM t",
                DatabaseType.MYSQL, DatabaseType.POSTGRESQL);
        assertThat(mysql.blocked()).isTrue();
        assertThat(mysql.blockingReasons().get(0))
                .containsIgnoringCase("CONVERT").containsIgnoringCase("character set");
        // Oracle's CONVERT is the same character-set routine under every spelling.
        SqlBodyConverter.ConversionResult oracle = converter.convert(
                "SELECT CONVERT(name, 'ZHS16GBK') FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(oracle.blocked()).isTrue();
        assertThat(oracle.blockingReasons().get(0))
                .containsIgnoringCase("CONVERT").containsIgnoringCase("character set");
    }

    @Test
    void convertInsideALiteralIsNotRewrittenOrBlocked() {
        String sql = "SELECT 'CONVERT(VARCHAR(10), x, 112)' AS note FROM t";
        assertThat(conv(sql, DatabaseType.SQLSERVER, DatabaseType.MYSQL)).isEqualTo(sql);
        assertThat(converter.convert(sql, DatabaseType.SQLSERVER, DatabaseType.MYSQL)
                .blocked()).isFalse();
    }

    // --- B8: CAST type names follow the target dialect, including MySQL's cast allowlist -

    @Test
    void castTypeNameIsMappedToTheTargetDialect() {
        assertThat(conv("SELECT CAST(name AS VARCHAR2(20)) FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL))
                .isEqualTo("SELECT CAST(name AS CHAR(20)) FROM t");
        // PostgreSQL text is not a SQL Server type; the column machinery maps it to
        // NVARCHAR(MAX), which T-SQL CAST accepts.
        String toSqlServer = conv("SELECT CAST(name AS VARCHAR(20)) FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.SQLSERVER);
        assertThat(toSqlServer).contains("CAST(name AS NVARCHAR(20))");
    }

    @Test
    void mysqlSignedAndUnsignedCastTargetsWidenLikeColumns() {
        // SIGNED is MySQL's CAST spelling for BIGINT; Oracle receives NUMBER(19).
        String toOracle = conv("SELECT CAST(amount AS SIGNED) FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE);
        assertThat(toOracle).contains("CAST(amount AS NUMBER(19))");
        // UNSIGNED outranges signed BIGINT, so it widens to an exact 20-digit decimal;
        // the abstract type machinery spells that DECIMAL(20,0) (Oracle stores it as NUMBER).
        String unsigned = conv("SELECT CAST(amount AS UNSIGNED) FROM t",
                DatabaseType.MYSQL, DatabaseType.ORACLE);
        assertThat(unsigned).contains("CAST(amount AS DECIMAL(20,0))");
        // Other families' integer casts arrive on MySQL as SIGNED.
        assertThat(conv("SELECT CAST(amount AS BIGINT) FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.MYSQL))
                .contains("CAST(amount AS SIGNED)");
    }

    @Test
    void castToATimestampOnMySqlBecomesDatetime() {
        // The column machinery renders MySQL's timestamp as DATETIME(6); CAST accepts the
        // precision form, and the key point is that it is no longer Oracle's TIMESTAMP name.
        assertThat(conv("SELECT CAST(ts AS TIMESTAMP) FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL))
                .contains("CAST(ts AS DATETIME");
    }

    /** An unknown/UDT type cannot be guessed: mapping it to TEXT would compile and lie. */
    @Test
    void castWithAnUnrecognizedTypeIsBlocked() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT CAST(x AS SOME_UDT(5)) FROM t",
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0))
                .containsIgnoringCase("CAST").contains("SOME_UDT");
        assertThat(result.sql()).contains("SOME_UDT(5)");
    }

    /** PostgreSQL's :: cast operator is syntax nowhere else speaks; block rather than emit it. */
    @Test
    void postgresDoubleColonCastIsBlocked() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT id::text FROM t", DatabaseType.POSTGRESQL, DatabaseType.MYSQL);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0)).contains("::").contains("CAST");
        assertThat(result.sql()).contains("id::text");
    }

    @Test
    void castInsideALiteralIsLeftAlone() {
        String sql = "SELECT 'CAST(x AS VARCHAR2(20))' AS note FROM t";
        assertThat(conv(sql, DatabaseType.ORACLE, DatabaseType.MYSQL)).isEqualTo(sql);
    }

    // --- B6: TRUE/FALSE boolean literals ----------------------------------------------

    /**
     * Oracle (pre-23) and SQL Server have no boolean constants; both encode booleans as
     * 1/0 numbers, so comparisons and select-list literals become 1 and 0.
     */
    @Test
    void booleanLiteralsBecomeOneAndZeroOnOracleAndSqlServer() {
        String sql = "SELECT * FROM t WHERE active = TRUE AND deleted = FALSE";
        String toOracle = conv(sql, DatabaseType.POSTGRESQL, DatabaseType.ORACLE);
        assertThat(toOracle).contains("active = 1").contains("deleted = 0");
        assertThat(toOracle).doesNotContain("TRUE").doesNotContain("FALSE");
        String toSqlServer = conv(sql, DatabaseType.MYSQL, DatabaseType.SQLSERVER);
        assertThat(toSqlServer).contains("active = 1").contains("deleted = 0");
    }

    @Test
    void booleanConstantInASelectListBecomesOneOnOracle() {
        assertThat(conv("SELECT TRUE AS x, FALSE AS y FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.ORACLE))
                .contains("SELECT 1 AS x, 0 AS y FROM t");
    }

    /** MySQL/Postgres/DB2/HANA speak TRUE/FALSE natively, so nothing changes. */
    @Test
    void booleanLiteralsStayOnFamiliesThatHaveThem() {
        String sql = "SELECT * FROM t WHERE active = TRUE";
        assertThat(conv(sql, DatabaseType.POSTGRESQL, DatabaseType.MYSQL)).isEqualTo(sql);
        assertThat(conv(sql, DatabaseType.MYSQL, DatabaseType.POSTGRESQL)).isEqualTo(sql);
        assertThat(conv(sql, DatabaseType.POSTGRESQL, DatabaseType.DB2)).isEqualTo(sql);
        assertThat(conv(sql, DatabaseType.POSTGRESQL, DatabaseType.HANA)).isEqualTo(sql);
    }

    /**
     * {@code x IS TRUE} is not the same as {@code x = 1} (it is false, not unknown, for NULL);
     * SQL Server has no spelling for it, so block instead of silently changing NULL behavior.
     */
    @Test
    void isTruePredicateToSqlServerIsBlocked() {
        SqlBodyConverter.ConversionResult result = converter.convert(
                "SELECT * FROM t WHERE active IS TRUE",
                DatabaseType.POSTGRESQL, DatabaseType.SQLSERVER);
        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0)).contains("IS TRUE");
        assertThat(result.sql()).contains("IS TRUE");
    }

    @Test
    void booleanLiteralInsideAStringOrQuotedNameIsNotRewritten() {
        assertThat(conv("SELECT 'TRUE' AS note FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.ORACLE))
                .isEqualTo("SELECT 'TRUE' AS note FROM t");
    }

    // --- B9: GROUP BY ROLLUP / CUBE / GROUPING SETS -----------------------------------

    /**
     * MySQL's legacy {@code GROUP BY cols WITH ROLLUP} is the same grouping as the standard
     * {@code ROLLUP(cols)} function the other families (and MySQL 8.0.12+) use.
     */
    @Test
    void mysqlWithRollupBecomesTheStandardRollupFunction() {
        String sql = "SELECT region, product, SUM(amount) FROM t GROUP BY region, product"
                + " WITH ROLLUP";
        String expected = "GROUP BY ROLLUP(region, product)";
        for (DatabaseType target : new DatabaseType[]{DatabaseType.POSTGRESQL,
                DatabaseType.ORACLE, DatabaseType.SQLSERVER, DatabaseType.DB2,
                DatabaseType.HANA}) {
            assertThat(conv(sql, DatabaseType.MYSQL, target)).contains(expected);
        }
        // Multi-line bodies and derived-table subqueries are rewritten as well.
        assertThat(conv("SELECT * FROM (SELECT a, SUM(x) FROM t\nGROUP BY a WITH ROLLUP) q",
                DatabaseType.MYSQL, DatabaseType.POSTGRESQL))
                .contains("GROUP BY ROLLUP(a)");
    }

    /** MySQL has neither CUBE nor GROUPING SETS, so a rename that compiles is impossible. */
    @Test
    void cubeAndGroupingSetsToMySqlAreBlockedAndKept() {
        String cube = "SELECT a, b, SUM(x) FROM t GROUP BY CUBE(a, b)";
        SqlBodyConverter.ConversionResult cubeResult = converter.convert(cube,
                DatabaseType.ORACLE, DatabaseType.MYSQL);
        assertThat(cubeResult.blocked()).isTrue();
        assertThat(cubeResult.blockingReasons().get(0)).contains("CUBE");
        assertThat(cubeResult.sql()).contains("CUBE(a, b)");

        String sets = "SELECT a, b, SUM(x) FROM t GROUP BY GROUPING SETS ((a), (b))";
        SqlBodyConverter.ConversionResult setsResult = converter.convert(sets,
                DatabaseType.POSTGRESQL, DatabaseType.MYSQL);
        assertThat(setsResult.blocked()).isTrue();
        assertThat(setsResult.blockingReasons().get(0)).contains("GROUPING SETS");
    }

    /** The standard ROLLUP function is native MySQL 8.0.12+, so it must not be blocked. */
    @Test
    void standardRollupReachesMySqlUntouched() {
        String sql = "SELECT a, b, SUM(x) FROM t GROUP BY ROLLUP(a, b)";
        assertThat(conv(sql, DatabaseType.ORACLE, DatabaseType.MYSQL)).contains(sql.substring(
                sql.indexOf("GROUP BY")));
    }

    @Test
    void withRollupMentionedInALiteralIsNotRewritten() {
        String sql = "SELECT 'GROUP BY a WITH ROLLUP' AS note FROM t";
        assertThat(conv(sql, DatabaseType.MYSQL, DatabaseType.POSTGRESQL)).isEqualTo(sql);
    }

    // --- S3: PostgreSQL/MySQL JSON access operators -----------------------------------

    /**
     * {@code ->} / {@code ->>} are the shared pseudo-compatible syntax of PostgreSQL and
     * MySQL (same operand shapes for keys and array indexes), so nothing is rewritten
     * between those two families.
     */
    @Test
    void jsonArrowOperatorsAreSharedBetweenPostgresAndMysql() {
        String pgToMysql = "SELECT data -> '$.a' ->> 'b' FROM t";
        assertThat(conv(pgToMysql, DatabaseType.POSTGRESQL, DatabaseType.MYSQL))
                .isEqualTo(pgToMysql);
        String mysqlToPg = "SELECT data->>'$.name' FROM t";
        assertThat(conv(mysqlToPg, DatabaseType.MYSQL, DatabaseType.POSTGRESQL))
                .isEqualTo(mysqlToPg);
    }

    /** No other family parses the arrow or path operators; block and name JSON alternatives. */
    @Test
    void jsonOperatorsAreBlockedForFamiliesWithoutThem() {
        SqlBodyConverter.ConversionResult arrow = converter.convert(
                "SELECT data ->> 'name' FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.ORACLE);
        assertThat(arrow.blocked()).isTrue();
        assertThat(arrow.blockingReasons().get(0)).contains("->>").contains("JSON");

        SqlBodyConverter.ConversionResult hana = converter.convert(
                "SELECT data -> '$.a' FROM t",
                DatabaseType.MYSQL, DatabaseType.HANA);
        assertThat(hana.blocked()).isTrue();
        assertThat(hana.blockingReasons().get(0)).contains("->").contains("JSON");

        SqlBodyConverter.ConversionResult path = converter.convert(
                "SELECT data #>> '{a,b}' FROM t",
                DatabaseType.POSTGRESQL, DatabaseType.SQLSERVER);
        assertThat(path.blocked()).isTrue();
        assertThat(path.blockingReasons().get(0)).contains("#>>");
    }

    @Test
    void jsonOperatorsInsideALiteralAreNotBlocked() {
        String sql = "SELECT 'data ->> x' AS note FROM t";
        assertThat(converter.convert(sql, DatabaseType.POSTGRESQL, DatabaseType.ORACLE)
                .blocked()).isFalse();
    }

    // --- D6: cross-family procedural routine bodies -----------------------------------

    @Test
    void proceduralRoutineAcrossDialectFamiliesIsBlocked() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\n"
                + "BEGIN\n"
                + "  FOR r IN (SELECT id FROM t) LOOP\n"
                + "    INSERT INTO log VALUES (r.id);\n"
                + "  END LOOP;\n"
                + "END;";

        SqlBodyConverter.ConversionResult result = converter.convertProcedure(
                sql, DatabaseType.ORACLE, DatabaseType.POSTGRESQL);

        assertThat(result.blocked()).isTrue();
        assertThat(result.blockingReasons().get(0))
                .contains("procedural routine body")
                .contains("DDL override");
    }

    @Test
    void plpgsqlRoutineAcrossFamiliesIsBlockedEvenWithoutABeginToken() {
        String sql = "CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$\n"
                + "DECLARE x int;\n"
                + "BEGIN PERFORM 1; END $$";

        assertThat(converter.convertProcedure(sql, DatabaseType.POSTGRESQL, DatabaseType.MYSQL)
                .blocked()).isTrue();
    }

    @Test
    void sameFamilyProceduralRoutineIsNotBlockedByTheProceduralMarker() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS BEGIN NULL; END;";

        assertThat(converter.convertProcedure(sql, DatabaseType.ORACLE, DatabaseType.DM)
                .blocked()).isFalse();
    }

    @Test
    void aSingleQueryRoutineWithoutProceduralMarkersCanStillBeConverted() {
        String sql = "CREATE PROCEDURE p() AS SELECT SYSDATE FROM dual";

        SqlBodyConverter.ConversionResult result = converter.convertProcedure(
                sql, DatabaseType.ORACLE, DatabaseType.MYSQL);

        assertThat(result.blocked()).isFalse();
        assertThat(result.sql()).contains("SELECT NOW()");
    }

    @Test
    void proceduralMarkerMentionedInsideALiteralDoesNotBlockRoutine() {
        String sql = "CREATE PROCEDURE p() AS SELECT 'BEGIN and DECLARE are words' AS note";

        assertThat(converter.convertProcedure(sql, DatabaseType.ORACLE, DatabaseType.MYSQL)
                .blocked()).isFalse();
    }
}
