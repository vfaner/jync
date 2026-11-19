package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType.DialectFamily;

/**
 * The index dictionary is keyed the way the product folds identifiers: Oracle- and DB2-family
 * dictionaries store unquoted names upper case, so a lower-case configured schema must be
 * folded before it is used as the schema pattern. Otherwise the driver returns no rows, and
 * the probe reports "unindexed" for a table that has the index.
 */
class CursorIndexProbeTest {

    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    private final ResultSet indexes = mock(ResultSet.class);
    private final ResultSet primaryKeys = mock(ResultSet.class);

    @BeforeEach
    void setUp() throws SQLException {
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(indexes);
        when(indexes.next()).thenReturn(false);
        when(metaData.getPrimaryKeys(any(), any(), any())).thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(false);
    }

    @Test
    void oracleFoldsTheSchemaBeforeAskingTheDictionary() throws SQLException {
        assertThat(CursorIndexProbe.columnLeadsIndex(connection, DialectFamily.ORACLE,
                "app", "orders", "id")).isFalse();

        verify(metaData).getIndexInfo(isNull(), eq("APP"),
                eq("orders"), eq(false),
                eq(true));
    }

    @Test
    void db2FoldsTheSchemaBeforeAskingTheDictionary() throws SQLException {
        assertThat(CursorIndexProbe.columnLeadsIndex(connection, DialectFamily.DB2,
                "app", "orders", "id")).isFalse();

        verify(metaData).getIndexInfo(isNull(), eq("APP"),
                eq("orders"), eq(false),
                eq(true));
    }

    @Test
    void postgresKeepsTheConfiguredCase() throws SQLException {
        assertThat(CursorIndexProbe.columnLeadsIndex(connection, DialectFamily.POSTGRES,
                "mySchema", "orders", "id")).isFalse();

        verify(metaData).getIndexInfo(isNull(), eq("mySchema"),
                eq("orders"), eq(false),
                eq(true));
    }

    @Test
    void mysqlKeepsTheSchemaInTheCatalogSlot() throws SQLException {
        assertThat(CursorIndexProbe.columnLeadsIndex(connection, DialectFamily.MYSQL,
                "ShopDb", "orders", "id")).isFalse();

        verify(metaData).getIndexInfo(eq("ShopDb"), isNull(),
                eq("orders"), eq(false),
                eq(true));
    }

    @Test
    void aPrimaryKeyIsAnIndexEvenWhenTheDictionaryReportsNone() throws SQLException {
        // TDengine 3.3.6: getIndexInfo answers with no rows at all, yet the ts primary
        // key still turns a range read on ts into a key lookup. Its driver keys
        // getPrimaryKeys on the catalog slot, so the swapped convention answers.
        ResultSet noKeyInCallerConvention = emptyResultSet();
        ResultSet keyInSwappedConvention = resultSetWithPrimaryKey("ts");
        when(metaData.getPrimaryKeys(isNull(), eq("probe"), eq("t")))
                .thenReturn(noKeyInCallerConvention);
        when(metaData.getPrimaryKeys(eq("probe"), isNull(), eq("t")))
                .thenReturn(keyInSwappedConvention);

        assertThat(CursorIndexProbe.columnLeadsIndex(connection, DialectFamily.GENERIC,
                "probe", "t", "ts")).isTrue();
    }

    @Test
    void aColumnBehindAnotherPrimaryKeyColumnIsNotIndexed() throws SQLException {
        ResultSet key = mock(ResultSet.class);
        when(key.next()).thenReturn(true, true, false);
        when(key.getString("COLUMN_NAME")).thenReturn("ts", "id");
        when(key.getShort("KEY_SEQ")).thenReturn((short) 1, (short) 2);
        when(metaData.getPrimaryKeys(isNull(), eq("probe"), eq("t"))).thenReturn(key);

        assertThat(CursorIndexProbe.columnLeadsIndex(connection, DialectFamily.GENERIC,
                "probe", "t", "id")).isFalse();
    }

    @Test
    void aPrimaryKeyInTheCallerConventionIsUsedWithoutSwapping() throws SQLException {
        ResultSet key = resultSetWithPrimaryKey("id");
        when(metaData.getPrimaryKeys(eq("ShopDb"), isNull(), eq("orders")))
                .thenReturn(key);

        assertThat(CursorIndexProbe.columnLeadsIndex(connection, DialectFamily.MYSQL,
                "ShopDb", "orders", "id")).isTrue();

        verify(metaData, never()).getPrimaryKeys(isNull(), eq("ShopDb"), eq("orders"));
    }

    @Test
    void anUnreadablePrimaryKeyIsUnknownRatherThanUnindexed() throws SQLException {
        when(metaData.getPrimaryKeys(any(), any(), any()))
                .thenThrow(new SQLException("no such dictionary"));

        assertThat(CursorIndexProbe.columnLeadsIndex(connection, DialectFamily.GENERIC,
                "probe", "t", "ts")).isNull();
    }

    private static ResultSet emptyResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private static ResultSet resultSetWithPrimaryKey(String column) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("COLUMN_NAME")).thenReturn(column);
        when(rs.getShort("KEY_SEQ")).thenReturn((short) 1);
        return rs;
    }
}
