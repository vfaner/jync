package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.TableMeta;

/**
 * The name arguments of getTables/getColumns are JDBC <em>patterns</em>: an underscore
 * matches any single character. A driver therefore answers a request for {@code user_info}
 * with {@code user-info}'s rows too, and merging those would silently give the table a
 * column it does not have.
 */
class GenericMetadataReaderTest {

    private final GenericMetadataReader reader = new GenericMetadataReader();

    @Test
    void columnsFromWildcardNameCollisionsAreFilteredOut() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn(null);

        ResultSet none = emptyResultSet();
        when(md.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(none);
        when(md.getPrimaryKeys(any(), any(), anyString())).thenReturn(none);
        when(md.getIndexInfo(any(), any(), anyString(), anyBoolean(), anyBoolean())).thenReturn(none);
        when(md.getImportedKeys(any(), any(), anyString())).thenReturn(none);

        // One row for the requested table, one for a name that only matches it as a pattern.
        ResultSet columns = mock(ResultSet.class);
        when(columns.next()).thenReturn(true, true, false);
        when(columns.getString("TABLE_NAME")).thenReturn("user_info", "user-info");
        when(columns.getString("TABLE_SCHEM")).thenReturn(null, null);
        when(columns.getString("COLUMN_NAME")).thenReturn("id", "evil");
        when(columns.getString("TYPE_NAME")).thenReturn("BIGINT", "VARCHAR");
        when(columns.getInt("DATA_TYPE")).thenReturn(java.sql.Types.BIGINT, java.sql.Types.VARCHAR);
        when(columns.getInt("ORDINAL_POSITION")).thenReturn(1, 1);
        when(md.getColumns(any(), any(), anyString(), anyString())).thenReturn(columns);

        TableMeta table = reader.readTable(conn, null, "user_info");

        assertThat(table.getColumns()).extracting(c -> c.getName()).containsExactly("id");
    }

    @Test
    void aReportedSchemaThatDiffersFromTheRequestedOneIsFilteredOut() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn(null);

        ResultSet none = emptyResultSet();
        when(md.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(none);
        when(md.getPrimaryKeys(any(), any(), anyString())).thenReturn(none);
        when(md.getIndexInfo(any(), any(), anyString(), anyBoolean(), anyBoolean())).thenReturn(none);
        when(md.getImportedKeys(any(), any(), anyString())).thenReturn(none);

        ResultSet columns = mock(ResultSet.class);
        when(columns.next()).thenReturn(true, true, false);
        // Same name in two schemas; only the requested one may survive. A driver that
        // reports no schema at all (MySQL) must not be filtered — that is the other test.
        when(columns.getString("TABLE_NAME")).thenReturn("orders", "orders");
        when(columns.getString("TABLE_SCHEM")).thenReturn("sales", "audit");
        when(columns.getString("COLUMN_NAME")).thenReturn("id", "id");
        when(columns.getString("TYPE_NAME")).thenReturn("BIGINT", "BIGINT");
        when(columns.getInt("DATA_TYPE")).thenReturn(java.sql.Types.BIGINT, java.sql.Types.BIGINT);
        when(columns.getInt("ORDINAL_POSITION")).thenReturn(1, 1);
        when(md.getColumns(any(), any(), anyString(), anyString())).thenReturn(columns);

        TableMeta table = reader.readTable(conn, "sales", "orders");

        assertThat(table.getColumns()).hasSize(1);
    }

    @Test
    void primaryKeyIndexAndFkRowsFromWildcardNameCollisionsAreFilteredOut() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn(null);

        ResultSet none = emptyResultSet();
        ResultSet columnsNone = emptyResultSet();
        when(md.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(none);
        when(md.getColumns(any(), any(), anyString(), anyString())).thenReturn(columnsNone);

        // getPrimaryKeys: the intruder row (name only matching as a LIKE pattern) would
        // overwrite the real key column, since both sit at KEY_SEQ 1.
        ResultSet pks = mock(ResultSet.class);
        when(pks.next()).thenReturn(true, true, false);
        when(pks.getString("TABLE_NAME")).thenReturn("user_info", "userXinfo");
        when(pks.getString("TABLE_SCHEM")).thenReturn(null, null);
        when(pks.getShort("KEY_SEQ")).thenReturn((short) 1, (short) 1);
        when(pks.getString("COLUMN_NAME")).thenReturn("id", "evil");
        when(md.getPrimaryKeys(any(), any(), anyString())).thenReturn(pks);

        // getIndexInfo: one real index and one belonging to the pattern-matched table.
        ResultSet indexes = mock(ResultSet.class);
        when(indexes.next()).thenReturn(true, true, false);
        when(indexes.getShort("TYPE")).thenReturn(
                (short) DatabaseMetaData.tableIndexOther,
                (short) DatabaseMetaData.tableIndexOther);
        when(indexes.getString("TABLE_NAME")).thenReturn("user_info", "userXinfo");
        when(indexes.getString("TABLE_SCHEM")).thenReturn(null, null);
        when(indexes.getString("INDEX_NAME")).thenReturn("idx_a", "idx_evil");
        when(indexes.getString("COLUMN_NAME")).thenReturn("id", "evil");
        when(md.getIndexInfo(any(), any(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(indexes);

        // getImportedKeys: match is on the FKTABLE_* columns.
        ResultSet fks = mock(ResultSet.class);
        when(fks.next()).thenReturn(true, true, false);
        when(fks.getString("FKTABLE_NAME")).thenReturn("user_info", "userXinfo");
        when(fks.getString("FKTABLE_SCHEM")).thenReturn(null, null);
        when(fks.getString("FK_NAME")).thenReturn("fk_a", "fk_evil");
        when(fks.getString("FKCOLUMN_NAME")).thenReturn("id", "evil");
        when(md.getImportedKeys(any(), any(), anyString())).thenReturn(fks);

        TableMeta table = reader.readTable(conn, null, "user_info");

        assertThat(table.getPrimaryKeys()).containsExactly("id");
        assertThat(table.getIndexes()).extracting(i -> i.getName()).containsExactly("idx_a");
        assertThat(table.getForeignKeys()).extracting(f -> f.getName()).containsExactly("fk_a");
    }

    @Test
    void theCountFallbackRunsAtMostOncePerTtlWindow() throws Exception {
        // 统计信息不可用时 estimateRowCount 兜底走 SELECT COUNT(*) —— 全表扫描。
        // 轮询周期以秒计，兜底结果必须在 TTL 窗口内复用，否则大表每 2 秒被全扫一次。
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getURL()).thenReturn("jdbc:mock:db");
        when(md.getIdentifierQuoteString()).thenReturn("\"");
        java.sql.Statement st = mock(java.sql.Statement.class);
        when(conn.createStatement()).thenReturn(st);
        ResultSet rs = mock(ResultSet.class);
        when(st.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(42L);

        assertThat(reader.estimateRowCount(conn, "S", "T")).isEqualTo(42L);
        assertThat(reader.estimateRowCount(conn, "S", "T")).isEqualTo(42L);

        org.mockito.Mockito.verify(st, org.mockito.Mockito.times(1)).executeQuery(anyString());
    }

    @Test
    void businessTablesWhoseNamesStartWithSysOrPgAreNotSystemObjects() {
        // RuoYi 等框架的业务表普遍叫 sys_user / sys_role / sys_menu；PG_ 前缀同理。
        // 旧逻辑按表名前缀过滤，既漏同步，又在 allowDrop 下把目标端这些表 DROP 掉。
        assertThat(reader.isSystemObject(null, "ruoyi", "sys_user")).isFalse();
        assertThat(reader.isSystemObject(null, "ruoyi", "sys_role")).isFalse();
        assertThat(reader.isSystemObject(null, "ruoyi", "sys_menu")).isFalse();
        assertThat(reader.isSystemObject(null, "public", "pg_jobs")).isFalse();
        assertThat(reader.isSystemObject(null, "app", "system_config")).isFalse();
    }

    @Test
    void engineOwnedSchemasAndCatalogsAreSystemObjects() {
        // Oracle SYS/SYSTEM owner, SQL Server sys schema, PG pg_catalog, 标准 information_schema.
        assertThat(reader.isSystemObject(null, "SYS", "USER$")).isTrue();
        assertThat(reader.isSystemObject(null, "sys", "databases")).isTrue();
        assertThat(reader.isSystemObject(null, "pg_catalog", "pg_class")).isTrue();
        assertThat(reader.isSystemObject(null, "information_schema", "tables")).isTrue();
        assertThat(reader.isSystemObject(null, "pg_temp_3", "x")).isTrue();
        assertThat(reader.isSystemObject(null, "pg_toast", "x")).isTrue();
        assertThat(reader.isSystemObject(null, "SYSIBM", "SYSTABLES")).isTrue();
        // MySQL-family: the engine owns the catalog, schema is reported null.
        assertThat(reader.isSystemObject("mysql", null, "user")).isTrue();
        assertThat(reader.isSystemObject("information_schema", null, "tables")).isTrue();
        assertThat(reader.isSystemObject(null, "APEX_030200", "wwv_flow_users")).isTrue();
    }

    @Test
    void recycleBinAndEngineGeneratedNamesAreSystemObjectsWhereverTheyLive() {
        // 这些是引擎在用户 schema 内自己生成的对象，只能按名字识别。
        assertThat(reader.isSystemObject(null, "APP", "BIN$abc==$0")).isTrue();
        assertThat(reader.isSystemObject(null, "APP", "MLOG$_T1")).isTrue();
        assertThat(reader.isSystemObject(null, "main", "sqlite_sequence")).isTrue();
    }

    @Test
    void listTableNamesKeepsRuoYiTablesAndDropsEngineSchemas() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(conn.getCatalog()).thenReturn("ruoyi");

        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, true, false);
        when(rs.getString("TABLE_NAME")).thenReturn("sys_user", "sys_role", "biz_order");
        when(rs.getString("TABLE_CAT")).thenReturn(null, null, null);
        when(rs.getString("TABLE_SCHEM")).thenReturn("ruoyi", "ruoyi", "ruoyi");
        when(md.getTables(any(), any(), anyString(), any(String[].class))).thenReturn(rs);

        assertThat(reader.listTableNames(conn, "ruoyi"))
                .containsExactly("biz_order", "sys_role", "sys_user");
    }

    private ResultSet emptyResultSet() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }
}
