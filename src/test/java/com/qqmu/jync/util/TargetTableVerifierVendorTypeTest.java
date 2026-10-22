package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * The target-side shape check must understand the same vendor types as the type mapper.
 *
 * <p>Two losses it used to take: a vendor code outside {@link Types} (Oracle's 100
 * {@code BINARY_FLOAT}, SQL Server's -157 {@code geometry}) was filed as "unknown — must
 * match exactly", so a table the generator had just built failed its own verification; and
 * types with no equality operator ({@code XMLTYPE}, {@code SQL_VARIANT}, geometry) were
 * admitted into the upsert's change guard, which then failed the statement on every row.
 */
class TargetTableVerifierVendorTypeTest {

    private record Target(String name, int jdbcType, int columnSize, String typeName) {
    }

    private static Connection connectionWithColumns(Target... columns) throws SQLException {
        Connection conn = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet rs = mock(ResultSet.class);
        List<Target> rows = List.of(columns);
        when(conn.getMetaData()).thenReturn(metaData);
        when(metaData.getColumns(any(), any(), anyString(), anyString())).thenReturn(rs);
        // Oracle targets also probe ALL_TAB_IDENTITY_COLS; an empty answer means "none".
        PreparedStatement probe = mock(PreparedStatement.class);
        ResultSet probeRows = mock(ResultSet.class);
        when(probe.executeQuery()).thenReturn(probeRows);
        when(probeRows.next()).thenReturn(false);
        when(conn.prepareStatement(anyString())).thenReturn(probe);

        AtomicInteger row = new AtomicInteger();
        when(rs.next()).thenAnswer(invocation -> row.getAndIncrement() < rows.size());
        // The verifier keeps only rows whose TABLE_NAME is the table it asked for ("T"/"t").
        when(rs.getString("TABLE_NAME")).thenReturn("T");
        AtomicInteger nameIdx = new AtomicInteger();
        when(rs.getString("COLUMN_NAME"))
                .thenAnswer(invocation -> rows.get(nameIdx.getAndIncrement()).name());
        AtomicInteger typeIdx = new AtomicInteger();
        when(rs.getInt("DATA_TYPE"))
                .thenAnswer(invocation -> rows.get(typeIdx.getAndIncrement()).jdbcType());
        AtomicInteger sizeIdx = new AtomicInteger();
        when(rs.getInt("COLUMN_SIZE"))
                .thenAnswer(invocation -> rows.get(sizeIdx.getAndIncrement()).columnSize());
        AtomicInteger typeNameIdx = new AtomicInteger();
        when(rs.getString("TYPE_NAME"))
                .thenAnswer(invocation -> rows.get(typeNameIdx.getAndIncrement()).typeName());
        return conn;
    }

    private static ColumnMeta sourceColumn(String name, int jdbcType, String typeName) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setTypeName(typeName);
        return column;
    }

    private static TableMeta tableOf(ColumnMeta... columns) {
        TableMeta table = new TableMeta();
        table.setName("T");
        for (ColumnMeta column : columns) {
            table.getColumns().add(column);
        }
        return table;
    }

    /**
     * MySQL 把库名放在 JDBC 的 catalog 里,schema 参数被忽略。把配置的库名当 schema 传,
     * Connector/J 会返回服务器上所有同名表的列(docker 实测 8.0.33:连 db_a、问 db_b,
     * 返回 db_a.t 与 db_b.t 两套列);同名表在另一个库里类型不同时,校验就会拿错类型
     * 判成 incompatible type。必须与 MySQLMetadataReader.catalogFor 同一套目录/模式对调。
     */
    @Test
    void mysqlTargetLooksColumnsUpInTheConfiguredDatabaseCatalog() throws SQLException {
        Connection conn = connectionWithColumns(new Target("C", Types.VARCHAR, 10, "varchar"));
        DatabaseMetaData metaData = conn.getMetaData();

        TargetTableVerifier.missingColumns(conn, "db_b", "t",
                tableOf(sourceColumn("C", Types.VARCHAR, "VARCHAR")), DatabaseType.MYSQL);

        verify(metaData).getColumns("db_b", null, "t", "%");
    }

    @Test
    void vendorTypesWithoutEqualityAreKeptOutOfTheChangeGuard() throws SQLException {
        Connection conn = connectionWithColumns(
                new Target("XMLCOL", 2007, 0, "XMLTYPE"),
                new Target("PLAINCOL", Types.VARCHAR, 100, "VARCHAR2"),
                new Target("VARCOL", -156, 0, "sql_variant"),
                new Target("GEOCOL", -157, 0, "geometry"));
        List<String> uncomparable = TargetTableVerifier.uncomparableColumns(conn, "APP", "T",
                DatabaseType.ORACLE, List.of("XMLCOL", "PLAINCOL", "VARCOL", "GEOCOL"));
        assertThat(uncomparable).containsExactly("XMLCOL", "VARCOL", "GEOCOL");
    }

    @Test
    void oracleBinaryFloatTargetMatchesItsSource() throws SQLException {
        Connection conn = connectionWithColumns(new Target("C", 100, 0, "BINARY_FLOAT"));
        String reason = TargetTableVerifier.verify(conn, "APP", "T",
                tableOf(sourceColumn("C", 100, "BINARY_FLOAT")), DatabaseType.ORACLE);
        assertThat(reason).isNull();
    }

    @Test
    void sqlServerUniqueIdentifierLandsInVarcharTarget() throws SQLException {
        Connection conn = connectionWithColumns(new Target("C", Types.VARCHAR, 36, "varchar"));
        String reason = TargetTableVerifier.verify(conn, "app", "t",
                tableOf(sourceColumn("C", -145, "uniqueidentifier")),
                DatabaseType.OPENGAUSS);
        assertThat(reason).isNull();
    }

    @Test
    void sqlServerGeometryLandsInByteaTarget() throws SQLException {
        Connection conn = connectionWithColumns(new Target("C", Types.BINARY, 0, "bytea"));
        String reason = TargetTableVerifier.verify(conn, "app", "t",
                tableOf(sourceColumn("C", -157, "geometry")), DatabaseType.POSTGRESQL);
        assertThat(reason).isNull();
    }

    /** Vendors understood, families still enforced: money is numeric, not text. */
    @Test
    void vendorNumericDoesNotMatchTextTarget() throws SQLException {
        Connection conn = connectionWithColumns(new Target("C", Types.VARCHAR, 40, "varchar"));
        String reason = TargetTableVerifier.verify(conn, "app", "t",
                tableOf(sourceColumn("C", -148, "money")), DatabaseType.POSTGRESQL);
        assertThat(reason).contains("incompatible type");
    }

    /** A source column the target lacks is still reported as missing, not as a type error. */
    @Test
    void missingColumnStillReported() throws SQLException {
        Connection conn = connectionWithColumns(new Target("OTHER", Types.VARCHAR, 40, "varchar"));
        assertThat(TargetTableVerifier.missingColumns(conn, "app", "t",
                tableOf(sourceColumn("C", Types.VARCHAR, "VARCHAR")))).hasSize(1);
    }
}
