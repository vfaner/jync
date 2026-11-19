package com.qqmu.jync.service.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

class TdengineMetadataReaderTest {

    private static final TdengineMetadataReader READER = new TdengineMetadataReader();

    @Test
    void claimsOnlyTdengine() {
        assertThat(READER.supports(DatabaseType.TDENGINE)).isTrue();
        assertThat(READER.supports(DatabaseType.CLICKHOUSE)).isFalse();
    }

    @Test
    void readsTimestampFirstTableWithATimestampOnlyKey() throws Exception {
        TableMeta table = read("events", false,
                row("ts", "TIMESTAMP", 0, ""),
                row("val", "VARCHAR", 32, ""));

        assertThat(table.getPrimaryKeys()).containsExactly("ts");
        assertThat(table.getColumns()).extracting(ColumnMeta::getName)
                .containsExactly("ts", "val");
        assertThat(table.getColumns().get(1).getJdbcType()).isEqualTo(Types.VARCHAR);
        assertThat(table.getColumns().get(1).getSize()).isEqualTo(32);
        assertThat(table.getColumns().get(1).isPrimaryKey()).isFalse();
    }

    @Test
    void declaredPrimaryKeyAndTagsJoinTheKey() throws Exception {
        TableMeta plain = read("meter", false,
                row("ts", "TIMESTAMP", 0, ""),
                row("sn", "VARCHAR", 64, "PRIMARY KEY"),
                row("val", "DOUBLE", 0, ""));
        assertThat(plain.getPrimaryKeys()).containsExactly("ts", "sn");

        TableMeta stable = read("meters", true,
                row("ts", "TIMESTAMP", 0, ""),
                row("val", "DOUBLE", 0, ""),
                row("sn", "NCHAR", 32, "TAG"),
                row("site", "INT", 0, "TAG"));
        assertThat(stable.getPrimaryKeys()).containsExactly("ts", "sn", "site");
        assertThat(stable.getColumns()).filteredOn(ColumnMeta::isPrimaryKey)
                .extracting(ColumnMeta::getName).containsExactly("ts", "sn", "site");
    }

    @Test
    void mapsEveryTypeOfTheSpecTable() throws Exception {
        TableMeta table = read("types", false,
                row("ts", "TIMESTAMP", 0, ""),
                row("u8", "TINYINT UNSIGNED", 0, ""),
                row("u16", "SMALLINT UNSIGNED", 0, ""),
                row("u32", "INT UNSIGNED", 0, ""),
                row("u64", "BIGINT UNSIGNED", 0, ""),
                row("dec", "DECIMAL(20, 4)", 0, ""),
                row("bin", "BINARY", 8, ""),
                row("vbin", "VARBINARY", 16, ""),
                row("geo", "GEOMETRY", 20, ""),
                row("js", "JSON", 0, ""));

        assertThat(column(table, "u8").getJdbcType()).isEqualTo(Types.SMALLINT);
        assertThat(column(table, "u16").getJdbcType()).isEqualTo(Types.INTEGER);
        assertThat(column(table, "u32").getJdbcType()).isEqualTo(Types.BIGINT);
        assertThat(column(table, "u64").getJdbcType()).isEqualTo(Types.DECIMAL);
        assertThat(column(table, "u64").getSize()).isEqualTo(20);
        assertThat(column(table, "u64").getDecimalDigits()).isZero();
        assertThat(column(table, "dec").getSize()).isEqualTo(20);
        assertThat(column(table, "dec").getDecimalDigits()).isEqualTo(4);
        assertThat(column(table, "bin").getJdbcType()).isEqualTo(Types.VARCHAR);
        assertThat(column(table, "vbin").getJdbcType()).isEqualTo(Types.VARBINARY);
        assertThat(column(table, "geo").getJdbcType()).isEqualTo(Types.VARBINARY);
        assertThat(column(table, "js").getJdbcType()).isEqualTo(Types.VARCHAR);
        assertThat(table.getColumns()).allSatisfy(c -> assertThat(c.isNullable()).isTrue());
    }

    @Test
    void listsNormalTablesAndStablesButNotChildTables() throws Exception {
        ResultSet tables = resultSet("meter");
        ResultSet stables = resultSet("meters");
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0).toString();
            return sql.contains("ins_stables") ? stables : tables;
        });
        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(statement);

        assertThat(READER.listTableNames(conn, "iot")).containsExactly("meter", "meters");
    }

    @Test
    void theRowCountProbeRunsAtMostOncePerTtlWindow() throws Exception {
        // TDengine exposes no row statistics, so the estimate is SELECT COUNT(*) — a full
        // scan of every child table behind a stable. A polling cycle resolves the strategy of
        // every table and asks for this estimate whenever no cursor column is configured, so
        // without the shared TTL cache a large stable would be scanned every cycle.
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getURL()).thenReturn("jdbc:TAOS-WS://count-cache/iot");
        Statement statement = mock(Statement.class);
        when(conn.createStatement()).thenReturn(statement);
        ResultSet rs = mock(ResultSet.class);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(42L);

        assertThat(READER.estimateRowCount(conn, "iot", "meters")).isEqualTo(42L);
        assertThat(READER.estimateRowCount(conn, "iot", "meters")).isEqualTo(42L);

        verify(statement, times(1)).executeQuery(anyString());
        // The count is qualified with the resolved database, backtick-quoted like every other
        // TDengine statement here.
        verify(statement).executeQuery("SELECT COUNT(*) FROM `iot`.`meters`");
    }

    @Test
    void resolvesTheSchemaFromTheDatabaseFunction() throws Exception {
        ResultSet rs = resultSet("iot");
        Statement statement = mock(Statement.class);
        when(statement.executeQuery("SELECT DATABASE()")).thenReturn(rs);
        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(statement);

        assertThat(READER.resolveDefaultSchema(conn)).isEqualTo("iot");
    }

    // --- helpers --------------------------------------------------------------------

    private record Row(String field, String type, int length, String note) {
    }

    private static Row row(String field, String type, int length, String note) {
        return new Row(field, type, length, note);
    }

    private static TableMeta read(String table, boolean stable, Row... rows) throws Exception {
        ResultSet describe = mock(ResultSet.class);
        OngoingStubbing<Boolean> next = when(describe.next());
        for (int i = 0; i < rows.length; i++) {
            next = next.thenReturn(true);
        }
        next.thenReturn(false);

        // Each stubbing must be completed before the next when(...) starts, or Mockito
        // reports an unfinished stubbing.
        OngoingStubbing<String> fields = when(describe.getString(1));
        for (Row row : rows) {
            fields = fields.thenReturn(row.field());
        }
        OngoingStubbing<String> types = when(describe.getString(2));
        for (Row row : rows) {
            types = types.thenReturn(row.type());
        }
        OngoingStubbing<Integer> lengths = when(describe.getInt(3));
        for (Row row : rows) {
            lengths = lengths.thenReturn(row.length());
        }
        OngoingStubbing<String> notes = when(describe.getString(4));
        for (Row row : rows) {
            notes = notes.thenReturn(row.note());
        }

        ResultSet stableRs = resultSet(stable ? table : null);
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenAnswer(inv ->
                inv.getArgument(0).toString().startsWith("DESCRIBE") ? describe : stableRs);
        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenReturn(statement);

        return READER.readTable(conn, "iot", table);
    }

    private static ColumnMeta column(TableMeta table, String name) {
        return table.column(name).orElseThrow();
    }

    private static ResultSet resultSet(String firstValue) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        if (firstValue == null) {
            when(rs.next()).thenReturn(false);
        } else {
            when(rs.next()).thenReturn(true, false);
            when(rs.getString(1)).thenReturn(firstValue);
        }
        return rs;
    }
}
