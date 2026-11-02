package com.qqmu.jync.service.migration.kingbase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.converter.PostgresDialect;

@ExtendWith(MockitoExtension.class)
class KingbaseTableWriterTest {

    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private PreparedStatement preparedStatement;

    private KingbaseTableWriter writer;

    @BeforeEach
    void setUp() {
        writer = new KingbaseTableWriter(connection, "public", new PostgresDialect());
    }

    @Test
    void createTableQuotesTheNameAndDeclaresTheIdPrimaryKey() throws Exception {
        when(connection.createStatement()).thenReturn(statement);

        writer.createTable(table(), "Order Details");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).execute(sql.capture());
        assertThat(sql.getValue())
                .contains("CREATE TABLE \"public\".\"Order Details\"")
                .contains("\"_id\" VARCHAR(24) NOT NULL")
                .contains("PRIMARY KEY (\"_id\")");
        verify(connection).commit();
    }

    @Test
    void dropTableUsesIfExistsAndCommits() throws Exception {
        when(connection.createStatement()).thenReturn(statement);

        writer.dropTable("orders");

        verify(statement).executeUpdate("DROP TABLE IF EXISTS \"public\".\"orders\"");
        verify(connection).commit();
    }

    @Test
    void aNullValueIsBoundWithItsDeclaredJdbcType() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeBatch()).thenReturn(new int[] {1});

        KingbaseTableWriter.BatchWriter batch =
                writer.openBatch("orders", table(), false);
        batch.add(java.util.Arrays.asList("64b000000000000000000001", null));
        int written = batch.flush();
        batch.close();

        assertThat(written).isEqualTo(1);
        verify(preparedStatement).setObject(1, "64b000000000000000000001");
        verify(preparedStatement).setNull(2, Types.INTEGER);
        verify(preparedStatement).addBatch();
        verify(preparedStatement).executeBatch();
        verify(connection).commit();
    }

    @Test
    void aBinaryValueIsBoundWithSetBytes() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeBatch()).thenReturn(new int[] {1});
        TableMeta table = table();
        ColumnMeta payload = new ColumnMeta();
        payload.setName("payload");
        payload.setJdbcType(Types.BLOB);
        payload.setTypeName("Binary");
        payload.setNullable(true);
        table.getColumns().add(payload);

        KingbaseTableWriter.BatchWriter batch = writer.openBatch("orders", table, false);
        byte[] bytes = {1, 2, 3};
        batch.add(java.util.Arrays.asList("64b000000000000000000001", 1, bytes));
        batch.add(java.util.Arrays.asList("64b000000000000000000002", 2, null));
        batch.flush();
        batch.close();

        // PostgreSQL's setObject sends a byte[] as an oid parameter, which BYTEA rejects;
        // setBytes binds it with the column's actual type, and a null binds as VARBINARY.
        verify(preparedStatement).setBytes(3, bytes);
        verify(preparedStatement).setNull(3, Types.VARBINARY);
    }

    @Test
    void anUpsertBatchConflictsOnTheId() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        writer.openBatch("orders", table(), true);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .contains("INSERT INTO \"public\".\"orders\"")
                .contains("ON CONFLICT (\"_id\") DO UPDATE")
                .contains("\"qty\" = EXCLUDED.\"qty\"");
    }

    @Test
    void aFailedBatchDoesNotCommit() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeBatch())
                .thenThrow(new java.sql.SQLException("disk full"));

        KingbaseTableWriter.BatchWriter batch =
                writer.openBatch("orders", table(), false);
        batch.add(List.of("64b000000000000000000001", 2));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(batch::flush))
                .isInstanceOf(java.sql.SQLException.class);
        verify(connection, org.mockito.Mockito.never()).commit();
        verify(connection).rollback();
    }

    @Test
    void countRowsReturnsTheStoredCount() throws Exception {
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(3L);

        assertThat(writer.countRows("orders")).isEqualTo(3L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).isEqualTo("SELECT COUNT(*) FROM \"public\".\"orders\"");
    }

    @Test
    void tableExistenceComesFromTheDriverMetadata() throws Exception {
        DatabaseMetaData metaData = org.mockito.Mockito.mock(DatabaseMetaData.class);
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(isNull(), eq("public"), eq("orders"),
                eq(new String[] {"TABLE"}))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        assertThat(writer.tableExists("orders")).isTrue();

        when(resultSet.next()).thenReturn(false);
        assertThat(writer.tableExists("orders")).isFalse();
    }

    @Test
    void targetColumnsAreReadFromTheDriverMetadata() throws Exception {
        DatabaseMetaData metaData = org.mockito.Mockito.mock(DatabaseMetaData.class);
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getColumns(isNull(), eq("public"), eq("orders"), eq("%")))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("COLUMN_NAME")).thenReturn("_id", "qty");
        when(resultSet.getInt("DATA_TYPE")).thenReturn(Types.VARCHAR, Types.INTEGER);
        when(resultSet.getString("TYPE_NAME")).thenReturn("varchar", "int4");
        when(resultSet.getInt("COLUMN_SIZE")).thenReturn(24, 10);
        when(resultSet.getInt("DECIMAL_DIGITS")).thenReturn(0, 0);
        when(resultSet.getString("IS_NULLABLE")).thenReturn("NO", "YES");
        when(resultSet.getString("COLUMN_DEF")).thenReturn(null, null);

        List<ColumnMeta> columns = writer.targetColumns("orders");

        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).getName()).isEqualTo("_id");
        assertThat(columns.get(0).isNullable()).isFalse();
        assertThat(columns.get(1).getName()).isEqualTo("qty");
        assertThat(columns.get(1).getJdbcType()).isEqualTo(Types.INTEGER);
        assertThat(columns.get(1).isNullable()).isTrue();
    }

    @Test
    void idExistsBindsTheRenderedId() throws Exception {
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        ColumnMeta id = table().getColumns().get(0);
        assertThat(writer.idExists("orders", id, "64b000000000000000000001")).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .isEqualTo("SELECT 1 FROM \"public\".\"orders\" WHERE \"_id\" = ?");
        verify(preparedStatement).setObject(1, "64b000000000000000000001");
    }

    @Test
    void idExistsBindsABinaryIdWithSetBytes() throws Exception {
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        ColumnMeta id = table().getColumns().get(0);
        id.setJdbcType(Types.BLOB);
        byte[] bytes = {1, 2, 3};

        assertThat(writer.idExists("orders", id, bytes)).isTrue();

        verify(preparedStatement).setBytes(1, bytes);
        verify(preparedStatement, never()).setObject(anyInt(), any());
    }

    @Test
    void metadataLookupsEscapeWildcardsInTheTableName() throws Exception {
        DatabaseMetaData metaData = org.mockito.Mockito.mock(DatabaseMetaData.class);
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getSearchStringEscape()).thenReturn("\\");
        when(metaData.getTables(isNull(), eq("public"), eq("orders\\_\\%"),
                eq(new String[] {"TABLE"}))).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertThat(writer.tableExists("orders_%")).isFalse();
    }

    @Test
    void schemaExistenceComesFromTheDriverMetadata() throws Exception {
        DatabaseMetaData metaData = org.mockito.Mockito.mock(DatabaseMetaData.class);
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getSchemas()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("TABLE_SCHEM")).thenReturn("public", "sales");

        assertThat(writer.schemaExists()).isTrue();

        assertThat(new KingbaseTableWriter(connection, "missing", new PostgresDialect())
                .schemaExists()).isFalse();
    }

    @Test
    void anUnknownSchemaListDoesNotBlockTheRun() throws Exception {
        DatabaseMetaData metaData = org.mockito.Mockito.mock(DatabaseMetaData.class);
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getSchemas()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertThat(writer.schemaExists()).isTrue();
    }

    @Test
    void aUniqueIndexOnAColumnIsDetected() throws Exception {
        DatabaseMetaData metaData = org.mockito.Mockito.mock(DatabaseMetaData.class);
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getIndexInfo(isNull(), eq("public"), eq("orders"), eq(true), eq(true)))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("COLUMN_NAME")).thenReturn("qty", "_id");

        assertThat(writer.hasUniqueIndex("orders", "_id")).isTrue();
        assertThat(writer.hasUniqueIndex("orders", "missing")).isFalse();
    }

    private static TableMeta table() {
        TableMeta table = new TableMeta();
        table.setName("orders");

        ColumnMeta id = new ColumnMeta();
        id.setName("_id");
        id.setJdbcType(Types.VARCHAR);
        id.setTypeName("ObjectId");
        id.setSize(24);
        id.setNullable(false);
        id.setPrimaryKey(true);

        ColumnMeta qty = new ColumnMeta();
        qty.setName("qty");
        qty.setJdbcType(Types.INTEGER);
        qty.setTypeName("Int32");
        qty.setNullable(true);

        table.getColumns().add(id);
        table.getColumns().add(qty);
        table.getPrimaryKeys().add("_id");
        return table;
    }
}
