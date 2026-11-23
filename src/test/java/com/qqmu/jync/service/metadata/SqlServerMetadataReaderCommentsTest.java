package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.MySqlDialect;

/**
 * SQL Server keeps comments in the {@code MS_Description} extended property, not in a form
 * that {@code DatabaseMetaData} reports as REMARKS. Without the dictionary read, a SQL Server
 * source produces comment-less target tables even though the target dialect supports them.
 */
class SqlServerMetadataReaderCommentsTest {

    private final SqlServerMetadataReader reader = new SqlServerMetadataReader();

    @Test
    void tableAndColumnCommentsComeFromExtendedProperties() throws Exception {
        Connection conn = mock(Connection.class);
        stubComments(conn, "订单主表",
                new String[] {"ID", "NAME"}, new String[] {"主键", "客户名称"});
        TableMeta table = table();

        reader.readComments(conn, "dbo", "ORDERS", table);

        assertThat(table.getRemarks()).isEqualTo("订单主表");
        assertThat(column(table, "ID").getRemarks()).isEqualTo("主键");
        assertThat(column(table, "NAME").getRemarks()).isEqualTo("客户名称");
    }

    @Test
    void readTableAppliesTheDictionaryComments() throws Exception {
        Connection conn = metadataConnection();
        stubComments(conn, "订单主表", new String[0], new String[0]);

        TableMeta table = reader.readTable(conn, "dbo", "ORDERS");

        assertThat(table.getRemarks()).isEqualTo("订单主表");
        verify(conn).prepareStatement(contains("UNION ALL"));
    }

    @Test
    void anUnreadableDictionaryLeavesJdbcRemarksAlone() throws Exception {
        Connection conn = mock(Connection.class);
        when(conn.prepareStatement(anyString()))
                .thenThrow(new SQLException("permission denied on sys.extended_properties"));
        TableMeta table = table();
        table.setRemarks("from jdbc");
        column(table, "ID").setRemarks("from jdbc");

        assertThatCode(() -> reader.readComments(conn, "dbo", "ORDERS", table))
                .doesNotThrowAnyException();
        assertThat(table.getRemarks()).isEqualTo("from jdbc");
        assertThat(column(table, "ID").getRemarks()).isEqualTo("from jdbc");
    }

    @Test
    void sqlServerCommentsReachMySqlTargetDdl() throws Exception {
        Connection conn = mock(Connection.class);
        stubComments(conn, "订单主表",
                new String[] {"ID", "NAME"}, new String[] {"主键", "客户名称"});
        TableMeta table = typedTable();

        reader.readComments(conn, "dbo", "ORDERS", table);

        String ddl = new MySqlDialect().getCreateTableSql(
                table, "db", "ORDERS", DatabaseType.SQLSERVER);
        assertThat(ddl)
                .contains("COMMENT='订单主表'")
                .contains("`ID` BIGINT COMMENT '主键'")
                .contains("`NAME` VARCHAR(50) COMMENT '客户名称'");
    }

    @Test
    void aNullSchemaDefaultsToDbo() throws Exception {
        Connection conn = mock(Connection.class);
        ResultSet tableRs = emptyResultSet();
        PreparedStatement tablePs = mock(PreparedStatement.class);
        when(tablePs.executeQuery()).thenReturn(tableRs);
        when(conn.prepareStatement(contains("UNION ALL"))).thenReturn(tablePs);

        reader.readComments(conn, null, "ORDERS", new TableMeta());

        verify(tablePs).setString(1, "dbo");
        verify(tablePs).setString(3, "dbo");
    }

    private static TableMeta table() {
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        table.getColumns().add(id);
        table.getColumns().add(name);
        return table;
    }

    private static ColumnMeta column(TableMeta table, String name) {
        return table.column(name).orElseThrow();
    }

    private static TableMeta typedTable() {
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setTypeName("BIGINT");
        id.setJdbcType(Types.BIGINT);
        id.setOrdinalPosition(1);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setTypeName("VARCHAR");
        name.setJdbcType(Types.VARCHAR);
        name.setSize(50);
        name.setOrdinalPosition(2);
        table.getColumns().add(id);
        table.getColumns().add(name);
        return table;
    }

    private static Connection metadataConnection() throws SQLException {
        ResultSet none = emptyResultSet();
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getTables(any(), any(), any(), any())).thenReturn(none);
        when(md.getPrimaryKeys(any(), any(), any())).thenReturn(none);
        when(md.getColumns(any(), any(), any(), any())).thenReturn(none);
        when(md.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(none);
        when(md.getImportedKeys(any(), any(), any())).thenReturn(none);
        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn(null);
        return conn;
    }

    private static ResultSet emptyResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private static void stubComments(Connection conn, String tableComment, String[] names,
                                     String[] comments) throws SQLException {
        int rows = (tableComment == null ? 0 : 1) + names.length;
        ResultSet rs = mock(ResultSet.class);
        Boolean[] next = new Boolean[rows + 1];
        for (int i = 0; i < rows; i++) {
            next[i] = true;
        }
        next[rows] = false;
        when(rs.next()).thenReturn(next[0],
                java.util.Arrays.copyOfRange(next, 1, next.length));

        String[] columnNames = new String[rows];
        String[] commentValues = new String[rows];
        int offset = 0;
        if (tableComment != null) {
            columnNames[0] = null;
            commentValues[0] = tableComment;
            offset = 1;
        }
        for (int i = 0; i < names.length; i++) {
            columnNames[offset + i] = names[i];
            commentValues[offset + i] = comments[i];
        }
        when(rs.getString(1)).thenReturn(columnNames[0],
                java.util.Arrays.copyOfRange(columnNames, 1, columnNames.length));
        when(rs.getString(2)).thenReturn(commentValues[0],
                java.util.Arrays.copyOfRange(commentValues, 1, commentValues.length));
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(contains("UNION ALL"))).thenReturn(ps);
    }

}
