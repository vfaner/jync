package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * Oracle's JDBC metadata only returns REMARKS when the connection enables
 * {@code remarksReporting} (off by default), so a structure sync used to create every
 * target table and column without its comment. The dictionary itself always has them:
 * {@code ALL_TAB_COMMENTS} and {@code ALL_COL_COMMENTS} are readable with the same
 * privileges that let the reader see the table at all.
 *
 * <p>达梦 and 崖山 keep the same dictionary views, so the reader serves all three. A
 * dictionary that cannot be read must degrade to "no comments", never fail the read.
 */
class OracleMetadataReaderCommentsTest {

    private final OracleMetadataReader reader = new OracleMetadataReader();

    @Test
    void tableAndColumnCommentsComeFromTheDictionary() throws Exception {
        Connection conn = mockConnection("Oracle");
        stubTableComments(conn, "订单主表");
        stubColumnComments(conn, new String[] {"ID", "NAME"}, new String[] {"主键", "客户名称"});
        TableMeta table = table();

        reader.readComments(conn, "HR", "ORDERS", table);

        assertThat(table.getRemarks()).isEqualTo("订单主表");
        assertThat(column(table, "ID").getRemarks()).isEqualTo("主键");
        assertThat(column(table, "NAME").getRemarks()).isEqualTo("客户名称");
    }

    @Test
    void aCommentlessColumnKeepsWhateverTheJdbcMetadataReported() throws Exception {
        Connection conn = mockConnection("Oracle");
        stubTableComments(conn, null);
        stubColumnComments(conn, new String[] {"ID", "NAME"}, new String[] {null, "客户名称"});
        TableMeta table = table();
        column(table, "ID").setRemarks("from jdbc");
        table.setRemarks("existing");

        reader.readComments(conn, "HR", "ORDERS", table);

        assertThat(table.getRemarks()).isEqualTo("existing");
        assertThat(column(table, "ID").getRemarks()).isEqualTo("from jdbc");
        assertThat(column(table, "NAME").getRemarks()).isEqualTo("客户名称");
    }

    @Test
    void anUnreadableDictionaryLeavesTheMetadataAsItWas() throws Exception {
        Connection conn = mockConnection("Oracle");
        when(conn.prepareStatement(contains("ALL_TAB_COMMENTS")))
                .thenThrow(new SQLException("ORA-00942: table or view does not exist"));
        when(conn.prepareStatement(contains("ALL_COL_COMMENTS")))
                .thenThrow(new SQLException("ORA-00942: table or view does not exist"));
        TableMeta table = table();

        assertThatCode(() -> reader.readComments(conn, "HR", "ORDERS", table))
                .doesNotThrowAnyException();
        assertThat(table.getRemarks()).isNull();
        assertThat(column(table, "NAME").getRemarks()).isNull();
    }

    @Test
    void dictionaryLookupParametersFoldIdentifiersWithTheRootLocale() throws Exception {
        // 字典查询参数必须用 ROOT 大写：tr-TR 下 "inventory" 会变成 "İNVENTORY"，
        // 与数据字典里存的 INVENTORY 不相等，表/列注释整体丢失。
        Connection conn = mockConnection("Oracle");
        ResultSet emptyTable = emptyResultSet();
        PreparedStatement tablePs = mock(PreparedStatement.class);
        when(tablePs.executeQuery()).thenReturn(emptyTable);
        when(conn.prepareStatement(contains("ALL_TAB_COMMENTS"))).thenReturn(tablePs);
        ResultSet emptyColumn = emptyResultSet();
        PreparedStatement columnPs = mock(PreparedStatement.class);
        when(columnPs.executeQuery()).thenReturn(emptyColumn);
        when(conn.prepareStatement(contains("ALL_COL_COMMENTS"))).thenReturn(columnPs);

        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            reader.readComments(conn, "inventory", "invoices", table());
        } finally {
            java.util.Locale.setDefault(previous);
        }

        verify(tablePs).setString(1, "INVENTORY");
        verify(tablePs).setString(2, "INVOICES");
        verify(columnPs).setString(1, "INVENTORY");
    }

    @Test
    void readTableFillsCommentsEvenWhenTheProductIsNotOracle() throws Exception {
        // 达梦 mimics the dictionary layout; its driver likewise reports no REMARKS.
        Connection conn = mockConnection("DM DBMS");
        stubTableComments(conn, "订单主表");
        stubColumnComments(conn, new String[] {"ID"}, new String[] {"主键"});

        TableMeta table = reader.readTable(conn, "HR", "ORDERS");

        assertThat(table.getRemarks()).isEqualTo("订单主表");
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

    private static Connection mockConnection(String productName) throws SQLException {
        ResultSet empty = emptyResultSet();
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(md.getDatabaseProductName()).thenReturn(productName);
        when(md.getTables(any(), any(), any(), any())).thenReturn(empty);
        when(md.getPrimaryKeys(any(), any(), any())).thenReturn(empty);
        when(md.getColumns(any(), any(), any(), any())).thenReturn(empty);
        when(md.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(empty);
        when(md.getImportedKeys(any(), any(), any())).thenReturn(empty);
        Connection conn = mock(Connection.class);
        when(conn.getMetaData()).thenReturn(md);
        return conn;
    }

    private static ResultSet emptyResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private static void stubTableComments(Connection conn, String comment) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(comment != null).thenReturn(false);
        if (comment != null) {
            when(rs.getString(1)).thenReturn(comment);
        }
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(contains("ALL_TAB_COMMENTS"))).thenReturn(ps);
    }

    private static void stubColumnComments(Connection conn, String[] names, String[] comments)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Boolean[] next = new Boolean[names.length + 1];
        for (int i = 0; i < names.length; i++) {
            next[i] = true;
        }
        next[names.length] = false;
        when(rs.next()).thenReturn(next[0], java.util.Arrays.copyOfRange(next, 1, next.length));
        when(rs.getString(1)).thenReturn(names[0],
                java.util.Arrays.copyOfRange(names, 1, names.length));
        when(rs.getString(2)).thenReturn(comments[0],
                java.util.Arrays.copyOfRange(comments, 1, comments.length));
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);
        when(conn.prepareStatement(contains("ALL_COL_COMMENTS"))).thenReturn(ps);
    }
}
