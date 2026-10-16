package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 失败摘要必须是一句能读懂的话，不是错误原文的堆砌。
 *
 * <p>实测事故:MySQL→Oracle 目标账号没有建表权限,三张表建表失败,弹窗里贴出三段完整的
 * {@code CREATE TABLE ... -> ORA-01031} —— 一屏 DDL,用户要按四次「确定」,还得自己从
 * 三段几乎相同的文本里找出"原来是权限不足"。同一份摘要还会写进任务表,在项目列表里以
 * 一个小 chip 显示(2000 字预算),那更是灾难。
 *
 * <p>所以摘要只保留:失败条数 + 原因(同一原因的合并计数) + 受影响的表名。
 */
class SyncResultErrorSummaryTest {

    /** 与 {@code DdlExecutor} 拼出来的一模一样:表名 + 完整 DDL + 驱动消息。 */
    private static String ddlFailure(String table) {
        return table + ": DDL failed: CREATE TABLE \"C##JYNC_MINER\".\"" + table.toUpperCase()
                + "\" ( \"ID\" NUMBER(19) NOT NULL, \"NAME\" VARCHAR2(50 CHAR) NOT NULL,"
                + " \"TEACHER_ID\" NUMBER(19), PRIMARY KEY (\"ID\") ) -> ORA-01031: 权限不足";
    }

    @Test
    void theSameCauseOnSeveralTablesCollapsesToOneLine() {
        SyncResult result = new SyncResult();
        result.addError(ddlFailure("class"));
        result.addError(ddlFailure("course"));
        result.addError(ddlFailure("score"));

        String summary = result.summary();

        assertThat(summary)
                .as("三条同一原因的错误合成一行,点名三张表")
                .isEqualTo("FAILED: 3 error(s) — ORA-01031: 权限不足 (class, course, score)");
    }

    @Test
    void theDdlStatementItselfNeverReachesTheSummary() {
        // 这是用户报的原话:「报错信息太长了」。摘要里出现建表语句就等于没修。
        SyncResult result = new SyncResult();
        result.addError(ddlFailure("class"));
        result.addError(ddlFailure("course"));

        assertThat(result.summary())
                .doesNotContain("CREATE TABLE")
                .doesNotContain("NUMBER(19)")
                .doesNotContain("\n");
    }

    @Test
    void moreTablesThanFitAreFoldedIntoACount() {
        SyncResult result = new SyncResult();
        for (String t : List.of("t1", "t2", "t3", "t4", "t5")) {
            result.addError(ddlFailure(t));
        }

        assertThat(result.summary())
                .as("表名最多点三张,其余给数量")
                .isEqualTo("FAILED: 5 error(s) — ORA-01031: 权限不足 (t1, t2, t3, +2 more)");
    }

    @Test
    void twoDistinctCausesAreListedAndTheRestIsCounted() {
        SyncResult result = new SyncResult();
        result.addError(ddlFailure("class"));
        result.addError("orders: DDL failed: CREATE INDEX ... -> ORA-00942: table or view does not exist");
        result.addError("logs: DDL failed: ALTER TABLE ... -> ORA-01430: column being added already exists in table");

        String summary = result.summary();

        assertThat(summary)
                .as("原因最多列两类,超出的只说还有几项")
                .isEqualTo("FAILED: 3 error(s) — ORA-01031: 权限不足 (class)"
                        + " | ORA-00942: table or view does not exist (orders)"
                        + " | +1 more error(s)");
    }

    @Test
    void aLongSingleLineErrorIsClipped() {
        SyncResult result = new SyncResult();
        result.addError("orders: DDL failed: ALTER TABLE x -> You have an error in your SQL syntax;"
                + " check the manual that corresponds to your MySQL server version for the right"
                + " syntax to use near 'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4' at line 1");

        String summary = result.summary();

        assertThat(summary)
                .as("没有 Oracle 式错误码时按字数截断,不能整段照搬")
                .startsWith("FAILED: 1 error(s) — You have an error in your SQL syntax")
                .contains("...")
                .hasSizeLessThan(200);
    }

    @Test
    void aMultiLineDriverMessageIsFlattenedToOneLine() {
        SyncResult result = new SyncResult();
        result.addError("orders: DDL failed: CREATE TABLE x -> ORA-00907: missing right parenthesis\n"
                + "Help: https://docs.oracle.com/error-help/db/ora-00907/");

        assertThat(result.summary())
                .as("驱动消息里的换行会把一行摘要变成多行,必须压平")
                .doesNotContain("\n");
    }

    @Test
    void anErrorWithoutATablePrefixStillReadsAsOneSentence() {
        SyncResult result = new SyncResult();
        result.addError("Connection or query failure: Unable to acquire JDBC Connection");

        assertThat(result.summary())
                .as("不是每类错误都有表名;没有就只报原因,不要硬套括号")
                .isEqualTo("FAILED: 1 error(s) — Connection or query failure:"
                        + " Unable to acquire JDBC Connection");
    }

    @Test
    void theSameTableTwiceIsNamedOnce() {
        SyncResult result = new SyncResult();
        result.addError(ddlFailure("class"));
        result.addError(ddlFailure("class"));

        assertThat(result.summary())
                .isEqualTo("FAILED: 2 error(s) — ORA-01031: 权限不足 (class)");
    }

    @Test
    void anErrorCarryingNothingButATableNameStillReads() {
        SyncResult result = new SyncResult();
        result.addError("class: ");

        assertThat(result.summary())
                .as("话没说完的错误不能压出空括号或空原因")
                .isEqualTo("FAILED: 1 error(s) — no detail recorded (class)");
    }

    @Test
    void aFailedRunWithoutErrorEntriesSaysSo() {
        // success 只能由 addError 翻成 false,今天到不了这里;但 setSuccess 是公开的,
        // 真到了也不能输出 "FAILED: 0 error(s) — "。
        SyncResult result = new SyncResult();
        result.setSuccess(false);

        assertThat(result.summary()).isEqualTo("FAILED: no error detail");
    }
}
