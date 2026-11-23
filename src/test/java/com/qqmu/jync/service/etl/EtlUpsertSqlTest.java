package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * The SQL the target executes in {@link com.qqmu.jync.model.EtlWriteMode#UPSERT} mode.
 *
 * <p>Identifiers here are deliberately unquoted, like every other statement the ETL runner
 * builds: the target table is created unquoted too, so both sides fold the same way and an
 * Oracle target created as {@code dst} is reachable as {@code dst}, not as {@code "dst"}.
 */
class EtlUpsertSqlTest {

    @Test
    void mysqlUsesOneStatementThatUpdatesEveryNonKeyColumn() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.MYSQL, "dst",
                List.of("id", "name", "amount"), List.of("id")))
                .isEqualTo("INSERT INTO dst (id, name, amount) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE name = VALUES(name),"
                        + " amount = VALUES(amount)");
    }

    @Test
    void mariadbSharesTheMysqlForm() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.MARIADB, "dst",
                List.of("id", "name"), List.of("id")))
                .contains("ON DUPLICATE KEY UPDATE name = VALUES(name)");
    }

    @Test
    void aKeyOnlyTableStillHasAValidMysqlStatement() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.MYSQL, "dst",
                List.of("id"), List.of("id")))
                .isEqualTo("INSERT INTO dst (id) VALUES (?)"
                        + " ON DUPLICATE KEY UPDATE id = id");
    }

    /**
     * PostgreSQL 系把冲突交给 {@code ON CONFLICT}:目标端按键更新,不再每行先 UPDATE 再
     * 探测/INSERT。冲突目标必须是一个已有唯一约束/主键(由 EtlUpsertIndexes 把关)。
     */
    @Test
    void postgresUsesOnConflictDoUpdate() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.POSTGRESQL, "dst",
                List.of("id", "name", "amount"), List.of("id")))
                .isEqualTo("INSERT INTO dst (id, name, amount) VALUES (?, ?, ?)"
                        + " ON CONFLICT (id) DO UPDATE SET"
                        + " name = EXCLUDED.name, amount = EXCLUDED.amount");
    }

    @Test
    void aKeyOnlyPostgresTableDoesNothingOnConflict() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.POSTGRESQL, "dst",
                List.of("id"), List.of("id")))
                .isEqualTo("INSERT INTO dst (id) VALUES (?) ON CONFLICT (id) DO NOTHING");
    }

    /**
     * openGauss / GaussDB 默认 {@code sql_compatibility='A'},解析器拒绝 ON CONFLICT 但接受
     * MySQL 风味(与同步侧 OpenGaussDialect 的取舍一致)。
     */
    @Test
    void openGaussAndGaussKeepTheMysqlFlavouredForm() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.OPENGAUSS, "dst",
                List.of("id", "name"), List.of("id")))
                .isEqualTo("INSERT INTO dst (id, name) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE name = VALUES(name)");
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.GAUSSDB, "dst",
                List.of("id", "name"), List.of("id")))
                .contains("ON DUPLICATE KEY UPDATE");
    }

    /** 神通 (OSCAR) 走 Oracle 兼容 MERGE，不生成 ON CONFLICT。 */
    @Test
    void oscarUsesOracleStyleMerge() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.OSCAR, "dst",
                List.of("id", "name"), List.of("id")))
                .isEqualTo("MERGE INTO dst t"
                        + " USING (SELECT ? AS id, ? AS name FROM DUAL) s"
                        + " ON (t.id = s.id)"
                        + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
                        + " WHEN NOT MATCHED THEN INSERT (id, name)"
                        + " VALUES (s.id, s.name)");
    }

    /**
     * 海量 Vastbase 接受 ON CONFLICT，只是不接受同步侧那层 WHERE 守卫；ETL 的单语句
     * upsert 本来就不带守卫，因此保持标准 PostgreSQL 写法。
     */
    @Test
    void vastbaseKeepsTheStandardPostgresForm() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.VASTBASE, "dst",
                List.of("id", "name"), List.of("id")))
                .isEqualTo("INSERT INTO dst (id, name) VALUES (?, ?)"
                        + " ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name");
    }

    /** Oracle 系(Oracle / 达梦 / 崖山):MERGE 的源行就是绑定列。 */
    @Test
    void oracleUsesMergeWithTheBoundRowAsTheSource() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.ORACLE, "dst",
                List.of("id", "name", "amount"), List.of("id")))
                .isEqualTo("MERGE INTO dst t"
                        + " USING (SELECT ? AS id, ? AS name, ? AS amount FROM DUAL) s"
                        + " ON (t.id = s.id)"
                        + " WHEN MATCHED THEN UPDATE SET t.name = s.name, t.amount = s.amount"
                        + " WHEN NOT MATCHED THEN INSERT (id, name, amount)"
                        + " VALUES (s.id, s.name, s.amount)");
    }

    /** 纯键表没有可更新列:MERGE 只保留 INSERT 分支,冲突时自然成为无操作。 */
    @Test
    void aKeyOnlyOracleTableStillMergesInserts() {
        String sql = EtlUpsertSql.nativeUpsert(DatabaseType.ORACLE, "dst",
                List.of("id"), List.of("id"));

        assertThat(sql).doesNotContain("WHEN MATCHED");
        assertThat(sql).endsWith("WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id)");
    }

    @Test
    void damengAndYashanShareTheOracleMerge() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.DM, "dst",
                List.of("id", "name"), List.of("id")))
                .startsWith("MERGE INTO dst t");
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.YASHANDB, "dst",
                List.of("id", "name"), List.of("id")))
                .startsWith("MERGE INTO dst t");
    }

    /**
     * T-SQL 的 MERGE 必须以分号结束;HOLDLOCK 防止并发下的键不存在→同时插入。
     */
    @Test
    void sqlServerUsesMergeWithAHoldlockAndTrailingSemicolon() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.SQLSERVER, "dst",
                List.of("id", "name", "amount"), List.of("id")))
                .isEqualTo("MERGE dst WITH (HOLDLOCK) AS t"
                        + " USING (VALUES (?, ?, ?)) AS s (id, name, amount)"
                        + " ON (t.id = s.id)"
                        + " WHEN MATCHED THEN UPDATE SET t.name = s.name, t.amount = s.amount"
                        + " WHEN NOT MATCHED THEN INSERT (id, name, amount)"
                        + " VALUES (s.id, s.name, s.amount);");
    }

    @Test
    void aKeyOnlySqlServerTableStillMergesInserts() {
        String sql = EtlUpsertSql.nativeUpsert(DatabaseType.SQLSERVER, "dst",
                List.of("id"), List.of("id"));

        assertThat(sql).doesNotContain("WHEN MATCHED");
        assertThat(sql).endsWith(";");
    }

    /** DB2 MERGE 需要 CAST 标记,HANA 的 UPSERT 绑定主键;二者保持模拟路径。 */
    @Test
    void db2HanaAndUnknownTargetsKeepTheEmulation() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.DB2, "dst",
                List.of("id", "name"), List.of("id"))).isNull();
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.HANA, "dst",
                List.of("id", "name"), List.of("id"))).isNull();
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.H2, "dst",
                List.of("id", "name"), List.of("id"))).isNull();
    }

    /**
     * Doris speaks the MySQL wire protocol but not MySQL's DML: it has no
     * ON DUPLICATE KEY UPDATE, so it must take the update-then-insert path.
     */
    @Test
    void dorisHasNoMysqlUpsertForm() {
        assertThat(EtlUpsertSql.nativeUpsert(DatabaseType.DORIS, "dst",
                List.of("id", "name"), List.of("id"))).isNull();
    }

    @Test
    void theEmulatedUpdateSetsTheNonKeyColumnsAndMatchesOnTheKey() {
        assertThat(EtlUpsertSql.update("dst", List.of("id", "name", "amount"), List.of("id")))
                .isEqualTo("UPDATE dst SET name = ?, amount = ? WHERE id = ?");
    }

    @Test
    void aKeyOnlyTableHasNoUpdateStatement() {
        assertThat(EtlUpsertSql.update("dst", List.of("id"), List.of("id"))).isNull();
    }

    @Test
    void aCompositeKeyBindsInTheConfiguredOrder() {
        assertThat(EtlUpsertSql.update("dst", List.of("a", "b", "c"), List.of("b", "a")))
                .isEqualTo("UPDATE dst SET c = ? WHERE b = ? AND a = ?");
        assertThat(EtlUpsertSql.selectByKey("dst", List.of("b", "a")))
                .isEqualTo("SELECT 1 FROM dst WHERE b = ? AND a = ?");
    }

    @Test
    void theConfiguredListIsTrimmedAndBlankEntriesAreDropped() {
        assertThat(EtlUpsertSql.parse(" id,  tenant_id ,, "))
                .containsExactly("id", "tenant_id");
        assertThat(EtlUpsertSql.parse(null)).isEmpty();
        assertThat(EtlUpsertSql.parse("  ")).isEmpty();
    }

    @Test
    void aKeyConfiguredTwiceIsUsedOnce() {
        // A duplicated key reached the DDL as PRIMARY KEY (id, id) and PostgreSQL's
        // ON CONFLICT (id, id); both reject it.
        assertThat(EtlUpsertSql.parse("id, ID, id")).containsExactly("id");
    }

    @Test
    void keysResolveAgainstTheResultLabelsIgnoringCase() {
        assertThat(EtlUpsertSql.resolve(List.of("ID", "name"), List.of("id")))
                .containsExactly("ID");
    }

    @Test
    void aKeyThatIsNotInTheResultIsNamedInTheError() {
        assertThatThrownBy(() -> EtlUpsertSql.resolve(List.of("id"), List.of("id", "tenant")))
                .isInstanceOf(IllegalArgumentException.class)
                // The run history translates the part before the colon.
                .hasMessage("error.etl.upsertKey.missing:tenant");
    }
}
