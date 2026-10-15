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
            new SqlDialectFactory(List.of(new OracleDialect()), new GenericSqlDialect());

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
    void damengAndYashanGetThePortableFallbackRegardlessOfVersion() {
        for (DatabaseType type : List.of(DatabaseType.DM, DatabaseType.YASHANDB)) {
            SqlDialect dialect = factory.forType(type, null);
            String ddl = dialect.getCreateTableSql(identityTable(), null, "orders",
                    DatabaseType.MYSQL);
            assertThat(ddl).as("%s: no Oracle identity grammar", type)
                    .doesNotContain("AS IDENTITY");
            assertThat(((OracleDialect) dialect).getPostCreateTableDdl(
                    identityTable(), null, "orders", DatabaseType.MYSQL))
                    .as("%s: sequence+trigger emulation", type).hasSize(2);
        }
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
