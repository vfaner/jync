package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The PostgreSQL family's discovery contract.
 *
 * <p>pgjdbc maps each JDBC table type to exactly one {@code pg_class.relkind}: "TABLE" is
 * 'r' only, "VIEW" is 'v' only. Asking for those alone hides declarative partitioned
 * tables ('p') and materialized views ('m') from the object picker and from the legacy
 * "all tables" mode, so they silently never sync. {@code getColumns} already accepts
 * both kinds, so widening the listing is the whole fix.
 */
class PostgresMetadataReaderTest {

    private final PostgresMetadataReader reader = new PostgresMetadataReader();

    private Connection connectionReturningNoRows() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        ResultSet empty = mock(ResultSet.class);
        when(empty.next()).thenReturn(false);
        when(md.getTables(any(), any(), eq("%"), any(String[].class))).thenReturn(empty);
        return conn;
    }

    @Test
    void listsDeclarativePartitionedTablesAlongsidePlainOnes() throws Exception {
        Connection conn = connectionReturningNoRows();

        reader.listTableNames(conn, "public");

        ArgumentCaptor<String[]> types = ArgumentCaptor.forClass(String[].class);
        verify(conn.getMetaData()).getTables(any(), any(), eq("%"), types.capture());
        assertThat(types.getValue())
                .as("pgjdbc maps 'TABLE' to relkind 'r' only; partitioned parents need "
                        + "'PARTITIONED TABLE'")
                .contains("TABLE", "PARTITIONED TABLE");
    }

    @Test
    void listsMaterializedViewsAlongsidePlainViews() throws Exception {
        Connection conn = connectionReturningNoRows();

        reader.listViewNames(conn, "public");

        ArgumentCaptor<String[]> types = ArgumentCaptor.forClass(String[].class);
        verify(conn.getMetaData()).getTables(any(), any(), eq("%"), types.capture());
        assertThat(types.getValue())
                .as("pgjdbc maps 'VIEW' to relkind 'v' only; materialized views need "
                        + "'MATERIALIZED VIEW'")
                .contains("VIEW", "MATERIALIZED VIEW");
    }

    @Test
    void aDriverRejectingTheWiderTypeNamesFallsBackToPlainTables() throws Exception {
        // The reader is shared with pgjdbc-derived forks (OpenGauss, KingBase, Oscar, …).
        // A fork whose getTables() rejects an unknown type name must not lose discovery
        // entirely: the widened call fails, the classic listing still answers.
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        ResultSet plain = mock(ResultSet.class);
        when(plain.next()).thenReturn(true, false);
        when(plain.getString("TABLE_NAME")).thenReturn("orders");
        when(plain.getString("TABLE_CAT")).thenReturn(null);
        when(plain.getString("TABLE_SCHEM")).thenReturn("public");
        when(md.getTables(any(), any(), eq("%"), any(String[].class)))
                .thenThrow(new java.sql.SQLException("unsupported table type"))
                .thenReturn(plain);

        assertThat(reader.listTableNames(conn, "public")).containsExactly("orders");
    }
}
