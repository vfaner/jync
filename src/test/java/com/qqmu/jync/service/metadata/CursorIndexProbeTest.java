package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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

    @BeforeEach
    void setUp() throws SQLException {
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getIndexInfo(any(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(indexes);
        when(indexes.next()).thenReturn(false);
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
}
