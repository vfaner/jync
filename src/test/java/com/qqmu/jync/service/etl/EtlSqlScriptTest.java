package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType.DialectFamily;

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

    // --- derived-table adaptation ---------------------------------------------------------

    @Test
    void aTopLevelOrderByIsRemovedForADerivedTable() {
        assertThat(EtlSqlScript.stripTopLevelOrderBy("SELECT id FROM src ORDER BY id"))
                .isEqualTo("SELECT id FROM src");
    }

    @Test
    void anOrderByInsideAWindowOrSubqueryIsKept() {
        String sql = "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS r"
                + " FROM (SELECT id FROM src ORDER BY id) x";
        assertThat(EtlSqlScript.stripTopLevelOrderBy(sql)).isEqualTo(sql);
        assertThat(EtlSqlScript.hasTopLevelOrderBy(sql)).isFalse();
    }

    @Test
    void aLimitingClauseKeepsTheTopLevelOrderBy() {
        for (String sql : List.of(
                "SELECT TOP 10 id FROM src ORDER BY id",
                "SELECT id FROM src ORDER BY id LIMIT 10",
                "SELECT id FROM src ORDER BY id OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY",
                "SELECT id FROM src ORDER BY id FETCH FIRST 10 ROWS ONLY",
                "SELECT id FROM src ORDER BY id FOR UPDATE")) {
            assertThat(EtlSqlScript.stripTopLevelOrderBy(sql)).isEqualTo(sql);
        }
    }

    @Test
    void db2ReadOnlyAndOptimizeClausesDoNotKeepARedundantOrderBy() {
        // FOR FETCH ONLY / OPTIMIZE FOR n ROWS 不限制行数:ORDER BY 在派生表里依旧是冗余的,
        // 而 DB2 没有 FETCH FIRST 时照样拒绝派生表内的 ORDER BY —— 留着它任务永远跑不起来。
        assertThat(EtlSqlScript.stripTopLevelOrderBy(
                "SELECT id FROM src ORDER BY id FOR FETCH ONLY", DialectFamily.DB2))
                .isEqualTo("SELECT id FROM src");
        assertThat(EtlSqlScript.stripTopLevelOrderBy(
                "SELECT id FROM src ORDER BY id OPTIMIZE FOR 10 ROWS", DialectFamily.DB2))
                .isEqualTo("SELECT id FROM src");
    }

    @Test
    void anUnquotedLimitOrOffsetAliasIsNotALimitingClause() {
        assertThat(EtlSqlScript.stripTopLevelOrderBy(
                "SELECT x AS limit FROM src ORDER BY x", DialectFamily.SQLSERVER))
                .isEqualTo("SELECT x AS limit FROM src");
        assertThat(EtlSqlScript.stripTopLevelOrderBy(
                "SELECT x AS offset FROM src ORDER BY x", DialectFamily.SQLSERVER))
                .isEqualTo("SELECT x AS offset FROM src");
    }

    @Test
    void offsetWithAVariableOrHexCountStillKeepsTheOrderBy() {
        // SQL Server OFFSET @p ROWS / DB2 OFFSET :n ROWS / 十六进制计数：计数不是字面数字，
        // 但 OFFSET 仍是真限流子句，剥掉 ORDER BY 会让派生表里的 OFFSET 失去排序而报错。
        assertThat(EtlSqlScript.stripTopLevelOrderBy(
                "SELECT id FROM src ORDER BY id OFFSET @p ROWS", DialectFamily.SQLSERVER))
                .isEqualTo("SELECT id FROM src ORDER BY id OFFSET @p ROWS");
        assertThat(EtlSqlScript.stripTopLevelOrderBy(
                "SELECT id FROM src ORDER BY id OFFSET :n ROWS", DialectFamily.DB2))
                .isEqualTo("SELECT id FROM src ORDER BY id OFFSET :n ROWS");
        assertThat(EtlSqlScript.stripTopLevelOrderBy(
                "SELECT id FROM src ORDER BY id OFFSET 0x10 ROWS", DialectFamily.SQLSERVER))
                .isEqualTo("SELECT id FROM src ORDER BY id OFFSET 0x10 ROWS");
    }

    @Test
    void bareOffsetAndRowsColumnNamesAreNotALimitingClause() {
        // offset / rows are not reserved in T-SQL or DB2; a pagination-metadata table may name
        // its columns so. Adjacent bare identifiers must not be read as the OFFSET ... ROWS clause.
        assertThat(EtlSqlScript.stripTopLevelOrderBy(
                "SELECT offset, rows FROM t ORDER BY id", DialectFamily.SQLSERVER))
                .isEqualTo("SELECT offset, rows FROM t");
    }

    @Test
    void forXmlAndForJsonKeepTheTopLevelOrderBy() {
        for (String sql : List.of(
                "SELECT id FROM src ORDER BY id FOR XML PATH",
                "SELECT id FROM src ORDER BY id FOR JSON AUTO")) {
            assertThat(EtlSqlScript.stripTopLevelOrderBy(sql, DialectFamily.SQLSERVER))
                    .isEqualTo(sql);
        }
    }

    @Test
    void orderByInsideAStringOrCommentDoesNotCount() {
        String sql = "SELECT 'ORDER BY x' AS note, \"ORDER BY\" FROM src -- ORDER BY y";
        assertThat(EtlSqlScript.stripTopLevelOrderBy(sql)).isEqualTo(sql);
        assertThat(EtlSqlScript.hasTopLevelOrderBy(sql)).isFalse();
    }

    @Test
    void startsWithWithSeesThroughLeadingComments() {
        assertThat(EtlSqlScript.startsWithWith(
                "-- note\nWITH c AS (SELECT 1 AS id) SELECT id FROM c")).isTrue();
        assertThat(EtlSqlScript.startsWithWith("SELECT id FROM c")).isFalse();
    }

    @Test
    void theLastStatementIsTheRowReturningOne() {
        assertThat(EtlSqlScript.lastStatement(
                "CREATE TEMP TABLE t (id INT); SELECT id FROM t"))
                .isEqualTo("SELECT id FROM t");
        assertThat(EtlSqlScript.lastStatement("-- nothing")).isNull();
    }

    // —— 方言族敏感的文本扫描 ——
    // 拆分必须按源库自己的词法规则理解文本:把 PostgreSQL 的 'C:\' 当成 MySQL 的转义会
    // 判定字符串未闭合并把整段脚本拒掉;把 # 当成表名分隔符则会把 MySQL 注释里的分号
    // 当成分句点,把半条语句发给服务器。

    @Test
    void aBackslashEscapesOnlyInsideTheMysqlFamily() {
        // MySQL: \' 转义引号,字符串继续,第一个分号才分句(旧默认行为不变)
        assertThat(EtlSqlScript.split("SELECT 'a\\';b' FROM t", DialectFamily.MYSQL))
                .containsExactly("SELECT 'a\\';b' FROM t");

        // PostgreSQL / Oracle: standard_conforming_strings,反斜杠是普通字符
        assertThat(EtlSqlScript.split("SELECT 'C:\\'; SELECT 2", DialectFamily.POSTGRES))
                .containsExactly("SELECT 'C:\\'", "SELECT 2");
        assertThat(EtlSqlScript.split("SELECT 'C:\\'; SELECT 2", DialectFamily.ORACLE))
                .containsExactly("SELECT 'C:\\'", "SELECT 2");
    }

    @Test
    void aHashStartsALineCommentOnlyInsideTheMysqlFamily() {
        assertThat(EtlSqlScript.split("SELECT 1 # x;y\n; SELECT 2", DialectFamily.MYSQL))
                .containsExactly("SELECT 1 # x;y", "SELECT 2");

        // SQL Server: #tmp 是本地临时表,不是注释
        assertThat(EtlSqlScript.split("SELECT * FROM #tmp; SELECT 2", DialectFamily.SQLSERVER))
                .containsExactly("SELECT * FROM #tmp", "SELECT 2");
    }

    @Test
    void blockCommentsNestOnlyWhereTheDialectSaysSo() {
        String sql = "SELECT 1 /* a /* b */; SELECT 2 */";
        // PostgreSQL / SQL Server: 内层的 */ 不结束外层,分号整段在注释里
        assertThat(EtlSqlScript.split(sql, DialectFamily.POSTGRES)).hasSize(1);
        assertThat(EtlSqlScript.split(sql, DialectFamily.SQLSERVER)).hasSize(1);
        // MySQL / Oracle: 不嵌套,第一个 */ 结束注释,后面的分号分句
        assertThat(EtlSqlScript.split(sql, DialectFamily.MYSQL)).hasSize(2);
    }

    @Test
    void keywordLookupSkipsAMysqlHashComment() {
        assertThat(EtlSqlScript.startsWithWith("# setup\nWITH x AS (SELECT 1) SELECT * FROM x",
                DialectFamily.MYSQL)).isTrue();
    }
}
