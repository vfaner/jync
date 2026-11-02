package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.migration.kingbase.KingbaseTableWriter;

/**
 * Runs the writer against a real KingbaseES instance. There is no public Kingbase container,
 * so this test is opt-in:
 *
 * <pre>
 * JYNC_KINGBASE_IT_URL=jdbc:kingbase8://host:54321/test \
 * JYNC_KINGBASE_IT_USER=system JYNC_KINGBASE_IT_PASSWORD=... \
 * mvn -q test -Dtest=KingbaseLiveIT
 * </pre>
 *
 * <p>Stock PostgreSQL covers the same syntax in {@link KingbaseWriterPostgresIT}; this test
 * exists to catch driver-specific behaviour the PostgreSQL stand-in cannot.
 */
@EnabledIfEnvironmentVariable(named = "JYNC_KINGBASE_IT_URL", matches = ".+")
class KingbaseLiveIT {

    private static final String TABLE = "jync_mongo_live_orders";

    @Test
    void createsInsertsUpsertsAndDropsOnRealKingbase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                System.getenv("JYNC_KINGBASE_IT_URL"),
                System.getenv("JYNC_KINGBASE_IT_USER"),
                System.getenv("JYNC_KINGBASE_IT_PASSWORD"))) {
            connection.setAutoCommit(false);
            String schema = connection.getSchema();
            KingbaseTableWriter writer =
                    new KingbaseTableWriter(connection, schema, new PostgresDialect());
            try {
                drop(writer);
                TableMeta table = ordersTable();

                writer.createTable(table, TABLE);
                assertThat(writer.tableExists(TABLE)).isTrue();

                try (KingbaseTableWriter.BatchWriter batch =
                             writer.openBatch(TABLE, table, false)) {
                    batch.add(List.of("a", "first", 3));
                    batch.add(List.of("b", null, 5));
                    assertThat(batch.flush()).isEqualTo(2);
                }
                assertThat(writer.countRows(TABLE)).isEqualTo(2);

                try (KingbaseTableWriter.BatchWriter batch =
                             writer.openBatch(TABLE, table, true)) {
                    batch.add(List.of("a", "updated", 9));
                    batch.flush();
                }
                assertThat(writer.countRows(TABLE)).isEqualTo(1);
                assertThat(queryLong(connection,
                        "SELECT qty FROM " + writer.qualified(TABLE)
                                + " WHERE \"_id\" = 'a'")).isEqualTo(9);

                ColumnMeta id = table.column("_id").orElseThrow();
                assertThat(writer.idExists(TABLE, id, "a")).isTrue();
                assertThat(writer.idExists(TABLE, id, "missing")).isFalse();
            } finally {
                drop(writer);
            }
        }
    }

    private static void drop(KingbaseTableWriter writer) throws Exception {
        if (writer.tableExists(TABLE)) {
            writer.dropTable(TABLE);
        }
    }

    private static TableMeta ordersTable() {
        TableMeta table = new TableMeta();
        table.setName(TABLE);
        table.getColumns().add(column("_id", Types.VARCHAR, "ObjectId", 24, false));
        table.getColumns().add(column("name", Types.CLOB, "String", null, true));
        table.getColumns().add(column("qty", Types.INTEGER, "Int32", null, true));
        table.getPrimaryKeys().add("_id");
        return table;
    }

    private static ColumnMeta column(String name, int jdbcType, String typeName, Integer size,
                                     boolean nullable) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setTypeName(typeName);
        column.setSize(size);
        column.setNullable(nullable);
        column.setPrimaryKey("_id".equals(name));
        return column;
    }

    private static long queryLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }
}
