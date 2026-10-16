package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 目标把「值没变的重放」也算成改动时，每轮的安全余量重放都会刷出一条变更日志 —— 实测
 * MySQL 源 + Oracle 目标空闲时每轮报一次 updated。原因不在源端：游标窗口的 1 秒余量每轮
 * 重发同一批行（设计如此，防迟到提交），MySQL 目标对「值一样」的 upsert 报 0 影响行，于是
 * 引擎跳过；Oracle 的 MERGE 报 1，落在「分不清插入还是更新」那一类里，被记成 updated。
 *
 * <p>所以这四个方言的 upsert 要能自己判断「确实有差异才更新」。条件必须是 NULL 安全的：
 * 偷懒写成 {@code a <> b} 会把 NULL→值的真实变化当成没变，那是丢数据，比多报严重得多。
 */
class UpsertChangeGuardTest {

    private static final List<String> COLUMNS = List.of("id", "name", "amount");
    private static final List<String> PK = List.of("id");
    private static final List<String> UPDATABLE = List.of("name", "amount");

    private final OracleDialect oracle = new OracleDialect();
    private final PostgresDialect postgres = new PostgresDialect();
    private final SqlServerDialect sqlServer = new SqlServerDialect();
    private final Db2Dialect db2 = new Db2Dialect();

    @Test
    void oracleOnlyUpdatesAMatchedRowWhenSomethingDiffers() {
        String sql = oracle.getUpsertSql("s", "orders", COLUMNS, PK, types(), true);

        assertThat(normalize(sql)).contains("WHEN MATCHED THEN UPDATE SET");
        assertThat(normalize(sql).indexOf(" WHERE "))
                .as("MERGE 的 UPDATE 子句要有 WHERE，否则无变化的行也算被改过")
                .isGreaterThan(normalize(sql).indexOf("WHEN MATCHED THEN UPDATE SET"));
        assertNullSafeGuardForEveryColumn(sql, "T.$", "S.$");
        assertThat(normalize(sql)).contains("WHEN NOT MATCHED THEN INSERT");
    }

    @Test
    void postgresOnlyUpdatesAMatchedRowWhenSomethingDiffers() {
        String sql = postgres.getUpsertSql("s", "orders", COLUMNS, PK, types(), true);

        assertThat(normalize(sql).indexOf(" WHERE ")).isGreaterThan(-1);
        // 目标行这一侧必须带关系名：只写裸列名时 PostgreSQL 报 column reference "name" is
        // ambiguous（docker 实测，VALUES 那一行的列也在作用域里）。
        assertNullSafeGuardForEveryColumn(sql, "ORDERS.$", "EXCLUDED.$");
    }

    @Test
    void sqlServerOnlyUpdatesAMatchedRowWhenSomethingDiffers() {
        // SQL Server 的 MERGE 不接受 WHEN MATCHED THEN UPDATE SET ... WHERE，条件挂在
        // WHEN MATCHED AND <条件> THEN 上；条件不成立时该分支不触发，影响行数就是 0。
        String sql = sqlServer.getUpsertSql("s", "orders", COLUMNS, PK, types(), true);

        assertThat(normalize(sql)).contains("WHEN MATCHED AND");
        assertNullSafeGuardForEveryColumn(sql, "T.$", "S.$");
    }

    @Test
    void db2OnlyUpdatesAMatchedRowWhenSomethingDiffers() {
        String sql = db2.getUpsertSql("s", "orders", COLUMNS, PK, types(), true);

        assertThat(normalize(sql)).contains("WHEN MATCHED AND");
        assertNullSafeGuardForEveryColumn(sql, "T.$", "S.$");
    }

    @Test
    void noGuardWhenTheTargetCannotVouchForEveryColumn() {
        // 调用方读目标表字典，发现有一列是不可比的（CLOB/BLOB/LONG/OTHER），于是传 false。
        // 这是必须守住的方向：在 Oracle 上比较 CLOB 会让整条语句报 ORA-22848，比多几条
        // 日志严重得多。
        assertThat(normalize(oracle.getUpsertSql("s", "orders", COLUMNS, PK, types(), false)))
                .doesNotContain(" WHERE ");
        assertThat(normalize(postgres.getUpsertSql("s", "orders", COLUMNS, PK, types(), false)))
                .doesNotContain(" WHERE ");
        assertThat(normalize(sqlServer.getUpsertSql("s", "orders", COLUMNS, PK, types(), false)))
                .doesNotContain("WHEN MATCHED AND");
        assertThat(normalize(db2.getUpsertSql("s", "orders", COLUMNS, PK, types(), false)))
                .doesNotContain("WHEN MATCHED AND");
    }

    @Test
    void anAllKeyTableDoesNotGetADanglingWhereClause() {
        // 每一列都是主键时没有可更新的列，压根没有 UPDATE 分支；不能凭空拼出一个 WHERE。
        List<String> onlyKey = List.of("id");

        for (SqlDialect dialect : List.of(oracle, postgres, sqlServer, db2)) {
            String sql = normalize(dialect.getUpsertSql("s", "orders", onlyKey, onlyKey,
                    types(), true));
            assertThat(sql)
                    .as("%s 的纯主键表不该出现空条件的 WHERE",
                            dialect.getClass().getSimpleName())
                    .doesNotContain("WHERE");
        }
    }

    /** 逐列钉住 NULL 安全的差异判定：值不同、NULL→值、值→NULL 三种都得被判成「有差异」。 */
    private static void assertNullSafeGuardForEveryColumn(String sql, String left, String right) {
        for (String column : UPDATABLE) {
            String c = column.toUpperCase(Locale.ROOT);
            String l = left.replace("$", c);
            String r = right.replace("$", c);
            assertThat(normalize(sql))
                    .as("%s 的差异判定必须是 NULL 安全的", column)
                    .contains(l + " <> " + r)
                    .contains("(" + l + " IS NULL AND " + r + " IS NOT NULL)")
                    .contains("(" + l + " IS NOT NULL AND " + r + " IS NULL)");
        }
    }

    /** 引号与方括号去掉、统一大写，好让断言只关心条件本身。 */
    private static String normalize(String sql) {
        return sql.toUpperCase(Locale.ROOT).replace("\"", "").replace("[", "").replace("]", "");
    }

    private static Map<String, Integer> types() {
        Map<String, Integer> types = new HashMap<>();
        types.put("id", Types.BIGINT);
        types.put("name", Types.VARCHAR);
        types.put("amount", Types.DECIMAL);
        return types;
    }
}
