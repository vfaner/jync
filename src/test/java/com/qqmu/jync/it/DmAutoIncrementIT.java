package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;

/**
 * Real DM8: the generated DDL uses native {@code AUTO_INCREMENT}, an explicitly supplied id
 * is written as-is, the counter continues after it for an id-less target insert, and the
 * dictionary reports the column as auto-increment. DM rejects {@code AUTO_INCREMENT} on a
 * non-key or non-integer column, which is why the dialect only emits it for integer primary
 * keys; those fallbacks are covered by unit tests.
 *
 * <pre>
 * JYNC_DM_IT_URL=jdbc:dm://host:5236?schema=SYSDBA \
 * JYNC_DM_IT_USER=SYSDBA JYNC_DM_IT_PASSWORD=... \
 * [JYNC_DM_IT_SCHEMA=SYSDBA] mvn -q test -Dtest=DmAutoIncrementIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "JYNC_DM_IT_URL", matches = ".+")
class DmAutoIncrementIT {

    private static final String TABLE = "JYNC_AI_IT";

    @Test
    void explicitIdsAreKeptAndTheNativeCounterContinues() throws Exception {
        String url = System.getenv("JYNC_DM_IT_URL");
        String user = envOrDefault("JYNC_DM_IT_USER", "SYSDBA");
        String password = System.getenv("JYNC_DM_IT_PASSWORD");
        assumeTrue(password != null && !password.isBlank(), "JYNC_DM_IT_PASSWORD is required");
        String schema = System.getenv("JYNC_DM_IT_SCHEMA");

        SqlDialect dm = new SqlDialectFactory(
                List.of(new OracleDialect(), new GenericSqlDialect()), new GenericSqlDialect())
                .forType(DatabaseType.DM, 11);
        TableMeta table = table();
        String create = dm.getCreateTableSql(table, schema, TABLE, DatabaseType.MYSQL);
        assertThat(create).contains("AUTO_INCREMENT");
        assertThat(dm.getPostCreateTableDdl(table, schema, TABLE, DatabaseType.MYSQL)).isEmpty();

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(true);
            executeQuietly(conn, "DROP TABLE " + qualified(schema, TABLE));
            try {
                execute(conn, create);
                execute(conn, "INSERT INTO " + qualified(schema, TABLE)
                        + "(\"ID\",\"NAME\") VALUES (769,'source')");
                execute(conn, "INSERT INTO " + qualified(schema, TABLE) + "(\"NAME\") VALUES ('target')");

                List<Long> ids = new ArrayList<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT \"ID\" FROM "
                             + qualified(schema, TABLE) + " ORDER BY \"ID\"")) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
                assertThat(ids).containsExactly(769L, 770L);

                try (ResultSet rs = conn.getMetaData().getColumns(null, schema, TABLE, "ID")) {
                    assertThat(rs.next()).as("column ID is in the dictionary").isTrue();
                    assertThat(rs.getString("IS_AUTOINCREMENT")).isEqualToIgnoringCase("YES");
                }
            } finally {
                executeQuietly(conn, "DROP TABLE " + qualified(schema, TABLE));
            }
        }
    }

    private static String qualified(String schema, String table) {
        return schema == null || schema.isBlank() ? "\"" + table + "\""
                : "\"" + schema + "\".\"" + table + "\"";
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static void executeQuietly(Connection conn, String sql) {
        try {
            execute(conn, sql);
        } catch (SQLException ignored) {
            // absent table / sequence is the expected first state
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static TableMeta table() {
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        id.setTypeName("BIGINT");
        id.setSize(19);
        id.setNullable(false);
        id.setPrimaryKey(true);
        id.setAutoIncrement(true);
        id.setOrdinalPosition(1);
        ColumnMeta name = new ColumnMeta();
        name.setName("name");
        name.setJdbcType(Types.VARCHAR);
        name.setTypeName("VARCHAR");
        name.setSize(50);
        name.setNullable(true);
        name.setOrdinalPosition(2);
        TableMeta table = new TableMeta();
        table.setName(TABLE);
        table.setColumns(new ArrayList<>(List.of(id, name)));
        table.setPrimaryKeys(List.of("id"));
        return table;
    }
}
