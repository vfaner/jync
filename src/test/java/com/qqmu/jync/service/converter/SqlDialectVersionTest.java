package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

/** The factory hands out a dialect configured for the target server's major version. */
class SqlDialectVersionTest {

    private final SqlDialectFactory factory =
            new SqlDialectFactory(List.of(new OracleDialect(), new MySqlDialect()),
                    new GenericSqlDialect());

    @Test
    void oracleElevenGetsTheSequenceTriggerFallback() {
        SqlDialect dialect = factory.forType(DatabaseType.ORACLE, 11);
        String ddl = dialect.getCreateTableSql(identityTable(), null, "orders",
                DatabaseType.MYSQL);
        assertThat(ddl).doesNotContain("AS IDENTITY");
        assertThat(((OracleDialect) dialect).getPostCreateTableDdl(
                identityTable(), null, "orders", DatabaseType.MYSQL))
                .hasSize(2);
    }

    @Test
    void unknownVersionGetsThePortableSequenceTriggerFallback() {
        // Identity syntax is unverifiable when the version is unknown; the sequence+trigger
        // emulation is legal SQL on every release, so it is the safe default.
        SqlDialect dialect = factory.forType(DatabaseType.ORACLE, null);
        String ddl = dialect.getCreateTableSql(identityTable(), null, "orders",
                DatabaseType.MYSQL);
        assertThat(ddl).doesNotContain("AS IDENTITY");
        assertThat(((OracleDialect) dialect).getPostCreateTableDdl(
                identityTable(), null, "orders", DatabaseType.MYSQL))
                .hasSize(2);
    }

    @Test
    void damengUsesItsNativeAutoIncrementRegardlessOfVersion() {
        SqlDialect dialect = factory.forType(DatabaseType.DM, null);
        String ddl = dialect.getCreateTableSql(identityTable(), null, "orders",
                DatabaseType.MYSQL);
        assertThat(ddl).contains("AUTO_INCREMENT").doesNotContain("AS IDENTITY");
        assertThat(((OracleDialect) dialect).getPostCreateTableDdl(
                identityTable(), null, "orders", DatabaseType.MYSQL))
                .as("native AUTO_INCREMENT needs no companion sequence/trigger").isEmpty();
    }

    @Test
    void yashanKeepsThePortableFallbackRegardlessOfVersion() {
        // YashanDB rejects AUTO_INCREMENT ("unexpected word"); its IDENTITY form accepts
        // explicit values, but no separate dialect selects it yet, so the emulation stays.
        SqlDialect dialect = factory.forType(DatabaseType.YASHANDB, null);
        String ddl = dialect.getCreateTableSql(identityTable(), null, "orders",
                DatabaseType.MYSQL);
        assertThat(ddl).doesNotContain("AS IDENTITY").doesNotContain("AUTO_INCREMENT");
        assertThat(((OracleDialect) dialect).getPostCreateTableDdl(
                identityTable(), null, "orders", DatabaseType.MYSQL))
                .hasSize(2);
    }

    // --- MySQL 5.7 vs 8.0 ----------------------------------------------------------------

    @Test
    void mysql57GetsTheVersionAndLosesWindowFunctionsAndExpressionDefaults() {
        SqlDialect dialect = factory.forType(DatabaseType.MYSQL, 5);

        assertThat(dialect.supportsWindowFunctions()).isFalse();
        assertThat(((AbstractSqlDialect) dialect)
                .columnDefinition(dateWithCurrentTimestamp(), DatabaseType.POSTGRESQL))
                .doesNotContain("DEFAULT");
    }

    @Test
    void mysql80GetsTheVersionAndKeepsBoth() {
        SqlDialect dialect = factory.forType(DatabaseType.MYSQL, 8);

        assertThat(dialect.supportsWindowFunctions()).isTrue();
        assertThat(((AbstractSqlDialect) dialect)
                .columnDefinition(dateWithCurrentTimestamp(), DatabaseType.POSTGRESQL))
                .contains("DATE DEFAULT (CURRENT_DATE)");
    }

    @Test
    void mariaDbIsJudgedOnItsOwnReleaseTrain() {
        assertThat(factory.forType(DatabaseType.MARIADB, 10).supportsWindowFunctions()).isTrue();
        assertThat(factory.forType(DatabaseType.MARIADB, 5).supportsWindowFunctions()).isFalse();
    }

    @Test
    void anUnknownMysqlVersionKeepsTheAttemptThenFallBackBehavior() {
        assertThat(factory.forType(DatabaseType.MYSQL, null).supportsWindowFunctions()).isTrue();
    }

    private static ColumnMeta dateWithCurrentTimestamp() {
        ColumnMeta d = new ColumnMeta();
        d.setName("D");
        d.setJdbcType(Types.DATE);
        d.setTypeName("DATE");
        d.setDefaultValue("CURRENT_TIMESTAMP");
        return d;
    }

    private static TableMeta identityTable() {
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        id.setTypeName("BIGINT");
        id.setSize(19);
        id.setNullable(false);
        id.setPrimaryKey(true);
        id.setAutoIncrement(true);
        TableMeta table = new TableMeta();
        table.setName("orders");
        table.setColumns(new ArrayList<>(List.of(id)));
        table.setPrimaryKeys(List.of("id"));
        return table;
    }
}
