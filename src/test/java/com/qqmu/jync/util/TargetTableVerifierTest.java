package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * P2: an existing target table of the right name but wrong shape must be named as the blocker,
 * while safe cross-type differences (INT→BIGINT, VARCHAR→CLOB) must pass.
 */
class TargetTableVerifierTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:shape_verify;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    void identicalShapesPass() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT, NAME VARCHAR(100))");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR)));

        assertThat(reason).isNull();
    }

    @Test
    void aMissingColumnIsReported() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR)));

        assertThat(reason).contains("missing column NAME");
    }

    @Test
    void aStringColumnBuiltAsNumericIsIncompatible() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT, NAME INT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR)));

        assertThat(reason).contains("column NAME", "incompatible type");
    }

    @Test
    void safeWideningWithinTheNumericFamilyPasses() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("ID", Types.INTEGER)));

        assertThat(reason).isNull();
    }

    @Test
    void aClobTargetAcceptsAVarcharSource() throws SQLException {
        createTarget("CREATE TABLE T (NOTE CLOB)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("NOTE", Types.VARCHAR)));

        assertThat(reason).isNull();
    }

    @Test
    void aTemporalColumnBuiltAsNumericIsIncompatible() throws SQLException {
        createTarget("CREATE TABLE T (WHEN_T INT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("WHEN_T", Types.TIMESTAMP)));

        assertThat(reason).contains("incompatible type");
    }

    @Test
    void mysqlYearIsNumericSoAnIntegerTargetPasses() throws SQLException {
        // Connector/J reports YEAR as JDBC DATE, but the row values are integer years.
        createTarget("CREATE TABLE T (C_YEAR SMALLINT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("C_YEAR", "YEAR", Types.DATE)));

        assertThat(reason).isNull();
    }

    @Test
    void mysqlYearBuiltAsDateIsIncompatible() throws SQLException {
        // A DATE target cannot receive the integer year value; the JDBC-DATE mislabel
        // must not make this look compatible.
        createTarget("CREATE TABLE T (C_YEAR DATE)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("C_YEAR", "YEAR", Types.DATE)));

        assertThat(reason).contains("incompatible type");
    }

    @Test
    void wideMysqlBitIsNumericSoAnIntegerTargetPasses() throws SQLException {
        // MySQL BIT(8) reports JDBC BIT but carries an 8-bit unsigned integer in bytes.
        createTarget("CREATE TABLE T (C_BIT SMALLINT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(bitCol("C_BIT", 8)));

        assertThat(reason).isNull();
    }

    @Test
    void wideMysqlBitBuiltAsBooleanIsIncompatible() throws SQLException {
        // A BIT(8) value (byte array) cannot land in a boolean column.
        createTarget("CREATE TABLE T (C_BIT BOOLEAN)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(bitCol("C_BIT", 8)));

        assertThat(reason).contains("incompatible type");
    }

    @Test
    void singleBitAgainstBooleanTargetPasses() throws SQLException {
        createTarget("CREATE TABLE T (C_FLAG BOOLEAN)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(bitCol("C_FLAG", 1)));

        assertThat(reason).isNull();
    }

    // —— F-9(docker 实测 PG→openGauss):JSON/JSONB/UUID 报 Types.OTHER(1111),
    // 但值是文本承载;目标手建 TEXT/VARCHAR 是推荐形态,必须通过 shape check。 ——

    @Test
    void pgJsonbAgainstTextTargetPasses() throws SQLException {
        createTarget("CREATE TABLE T (C_JSONB CLOB)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("C_JSONB", "jsonb", Types.OTHER)));

        assertThat(reason).isNull();
    }

    @Test
    void pgUuidAgainstVarcharTargetPasses() throws SQLException {
        createTarget("CREATE TABLE T (C_UUID VARCHAR(36))");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("C_UUID", "uuid", Types.OTHER)));

        assertThat(reason).isNull();
    }

    @Test
    void pgJsonAgainstTextTargetPasses() throws SQLException {
        createTarget("CREATE TABLE T (C_JSON VARCHAR(2000))");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("C_JSON", "json", Types.OTHER)));

        assertThat(reason).isNull();
    }

    @Test
    void textBorneOtherTypeBuiltAsNumericIsIncompatible() throws SQLException {
        // 守护:文本型 OTHER 列不能塞进数值目标。
        createTarget("CREATE TABLE T (C_JSONB INT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("C_JSONB", "jsonb", Types.OTHER)));

        assertThat(reason).contains("incompatible type");
    }

    @Test
    void anUnrecognizedOtherTypeStillRequiresAnExactMatch() throws SQLException {
        // 守护:不是所有 OTHER 都按文本放行 —— PG 的几何类型 point(getObject 是
        // PGpoint,不是字符串)仍须精确匹配,不能默默塞进 TEXT。
        createTarget("CREATE TABLE T (C_POINT VARCHAR(100))");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("C_POINT", "point", Types.OTHER)));

        assertThat(reason).contains("incompatible type");
    }

    @Test
    void oracleFamilyFoldsTheLookupNameToUpperCase() throws SQLException {
        // H2 — like Oracle — stores unquoted identifiers upper case. With an Oracle-family
        // target the verifier must fold the request the same way the DDL now does.
        createTarget("CREATE TABLE orders (id BIGINT, name VARCHAR(100))");

        String reason = TargetTableVerifier.verify(conn, null, "orders",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR)),
                DatabaseType.ORACLE);

        assertThat(reason).isNull();
    }

    @Test
    void nonFoldingDialectWithLowercaseRequestKeepsReportingMissingColumns() throws SQLException {
        // Control case: without the Oracle-family fold, the lowercase pattern "orders" does
        // not match H2/Oracle dictionary rows keyed ORDERS.
        createTarget("CREATE TABLE orders (id BIGINT)");

        String reason = TargetTableVerifier.verify(conn, null, "orders",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR)),
                DatabaseType.MYSQL);

        assertThat(reason).contains("missing column ID");
    }

    private void createTarget(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T");
            st.execute("DROP TABLE IF EXISTS ORDERS");
            st.execute(sql);
        }
    }

    private static ColumnMeta col(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        return column;
    }

    private static ColumnMeta col(String name, String typeName, int jdbcType) {
        ColumnMeta column = col(name, jdbcType);
        column.setTypeName(typeName);
        return column;
    }

    private static ColumnMeta bitCol(String name, int width) {
        ColumnMeta column = col(name, "BIT", Types.BIT);
        column.setSize(width);
        return column;
    }

    private static TableMeta source(ColumnMeta... columns) {
        TableMeta table = new TableMeta();
        table.setName("T");
        List<ColumnMeta> list = new ArrayList<>();
        for (ColumnMeta column : columns) {
            list.add(column);
        }
        table.setColumns(list);
        return table;
    }
}
