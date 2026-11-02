package com.qqmu.jync.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.migration.kingbase.KingbaseTableWriter;

/**
 * Exercises the KingbaseES writer against stock PostgreSQL, which shares its DDL and
 * {@code INSERT ... ON CONFLICT} syntax. Kingbase itself is covered by {@link KingbaseLiveIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class KingbaseWriterPostgresIT {

    private static final String TABLE = "jync_mongo_orders";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("jync")
                    .withUsername("jync")
                    .withPassword("jync");

    private Connection connection;
    private KingbaseTableWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        // The engine disables auto-commit before the writer commits the DDL and each batch.
        connection.setAutoCommit(false);
        writer = new KingbaseTableWriter(connection, "public", new PostgresDialect());
        dropIfPresent();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            dropIfPresent();
            connection.close();
        }
    }

    @Test
    void createsTheInferredColumnsAndInsertsRows() throws Exception {
        TableMeta table = ordersTable();
        writer.createTable(table, TABLE);

        List<ColumnMeta> columns = writer.targetColumns(TABLE);
        assertThat(columns).extracting(ColumnMeta::getName)
                .containsExactly("_id", "name", "payload", "created_at", "amount", "qty");
        assertThat(columns.get(0).getTypeName()).isEqualToIgnoringCase("varchar");
        assertThat(columns.get(0).getSize()).isEqualTo(24);
        assertThat(columns.get(1).getTypeName()).isEqualToIgnoringCase("text");
        assertThat(columns.get(2).getTypeName()).isEqualToIgnoringCase("bytea");
        assertThat(columns.get(3).getTypeName()).isEqualToIgnoringCase("timestamp");
        assertThat(columns.get(4).getTypeName()).isEqualToIgnoringCase("numeric");
        assertThat(columns.get(4).getSize()).isEqualTo(38);
        assertThat(columns.get(4).getDecimalDigits()).isEqualTo(10);
        // The PostgreSQL catalog spells INTEGER as int4; Kingbase reports either name.
        assertThat(columns.get(5).getTypeName()).isIn("integer", "int4");

        try (KingbaseTableWriter.BatchWriter batch = writer.openBatch(TABLE, table, false)) {
            batch.add(row("a", "first", new byte[] {1, 2}, 3));
            batch.add(row("b", null, null, 5));
            assertThat(batch.flush()).isEqualTo(2);
        }

        assertThat(writer.countRows(TABLE)).isEqualTo(2);
        assertThat(queryLong("SELECT qty FROM " + TABLE + " WHERE \"_id\" = 'b'")).isEqualTo(5);
        assertThat(queryString("SELECT name FROM " + TABLE + " WHERE \"_id\" = 'a'"))
                .isEqualTo("first");
    }

    @Test
    void upsertReplacesTheRowWithTheSameId() throws Exception {
        TableMeta table = ordersTable();
        writer.createTable(table, TABLE);
        try (KingbaseTableWriter.BatchWriter batch = writer.openBatch(TABLE, table, false)) {
            batch.add(row("a", "first", null, 3));
            batch.flush();
        }

        try (KingbaseTableWriter.BatchWriter batch = writer.openBatch(TABLE, table, true)) {
            batch.add(row("a", "updated", null, 9));
            batch.flush();
        }

        assertThat(writer.countRows(TABLE)).isEqualTo(1);
        assertThat(queryLong("SELECT qty FROM " + TABLE + " WHERE \"_id\" = 'a'")).isEqualTo(9);
        assertThat(queryString("SELECT name FROM " + TABLE + " WHERE \"_id\" = 'a'"))
                .isEqualTo("updated");
    }

    @Test
    void aFailedBatchRollsBackAndLeavesNoPartialRows() throws Exception {
        TableMeta table = ordersTable();
        writer.createTable(table, TABLE);

        try (KingbaseTableWriter.BatchWriter batch = writer.openBatch(TABLE, table, false)) {
            batch.add(row("a", "first", null, 3));
            // The primary key makes the second row fail inside executeBatch.
            batch.add(row("a", "duplicate", null, 4));
            org.assertj.core.api.Assertions.assertThatThrownBy(batch::flush)
                    .isInstanceOf(java.sql.SQLException.class);
        }

        assertThat(writer.countRows(TABLE)).isZero();
    }

    @Test
    void idExistsFindsInsertedRowsOnly() throws Exception {
        TableMeta table = ordersTable();
        writer.createTable(table, TABLE);
        try (KingbaseTableWriter.BatchWriter batch = writer.openBatch(TABLE, table, false)) {
            batch.add(row("a", "first", null, 3));
            batch.flush();
        }

        ColumnMeta id = table.column("_id").orElseThrow();
        assertThat(writer.idExists(TABLE, id, "a")).isTrue();
        assertThat(writer.idExists(TABLE, id, "missing")).isFalse();
    }

    @Test
    void dropTableRemovesTheTable() throws Exception {
        writer.createTable(ordersTable(), TABLE);
        assertThat(writer.tableExists(TABLE)).isTrue();

        writer.dropTable(TABLE);

        assertThat(writer.tableExists(TABLE)).isFalse();
    }

    private static TableMeta ordersTable() {
        TableMeta table = new TableMeta();
        table.setName(TABLE);
        table.getColumns().add(column("_id", Types.VARCHAR, "ObjectId", 24, null, false));
        table.getColumns().add(column("name", Types.CLOB, "String", null, null, true));
        table.getColumns().add(column("payload", Types.BLOB, "Binary", null, null, true));
        table.getColumns().add(column("created_at", Types.TIMESTAMP, "Date", null, null, true));
        table.getColumns().add(column("amount", Types.DECIMAL, "Decimal128", 38, 10, true));
        table.getColumns().add(column("qty", Types.INTEGER, "Int32", null, null, true));
        table.getPrimaryKeys().add("_id");
        return table;
    }

    private static ColumnMeta column(String name, int jdbcType, String typeName, Integer size,
                                     Integer decimalDigits, boolean nullable) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setTypeName(typeName);
        column.setSize(size);
        column.setDecimalDigits(decimalDigits);
        column.setNullable(nullable);
        column.setPrimaryKey("_id".equals(name));
        return column;
    }

    private static List<Object> row(String id, String name, byte[] payload, long qty) {
        return java.util.Arrays.asList(id, name, payload, Timestamp.from(Instant.now()),
                new BigDecimal("1.50"), (int) qty);
    }

    private void dropIfPresent() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public." + TABLE);
        }
        connection.commit();
    }

    private long queryLong(String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
    }

    private String queryString(String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
