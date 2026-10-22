package com.qqmu.jync.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
        // 建议本身也得是可执行的：老文案让人「让结构同步重建这张表」，而结构同步只会对
        // 已存在的表发 CREATE（必被拒），从来不会重建，用户照着做只会再撞一次墙。
        assertThat(reason)
                .as("缺列是补得上的，建议必须指向那条真能走通的路")
                .contains("structure sync")
                .doesNotContain("recreate it");
    }

    @Test
    void aStringColumnBuiltAsNumericIsIncompatible() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT, NAME INT)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR)));

        assertThat(reason).contains("column NAME", "incompatible type");
    }

    /**
     * getColumns 的 tableName 参数是 LIKE 模式(下划线匹配任意字符),所以读 A_B 同时会
     * 返回 AB 兄弟表的列。把它们并进同一张表会让校验误以为目标已有 EXTRA 列,补列逻辑
     * 因此永不触发,数据侧则每轮报一次 Unknown column——与 GenericMetadataReader 里
     * 同样问题的修法一致:读回每行的 TABLE_NAME,只保留真正被问的那张表。
     */
    @Test
    void columnsOfASiblingTableThatMatchesTheNamePatternAreNotMergedIn() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS A_B");
            st.execute("DROP TABLE IF EXISTS AXB");
            st.execute("CREATE TABLE A_B (ID BIGINT)");
            st.execute("CREATE TABLE AXB (ID BIGINT, EXTRA VARCHAR(10))");
        }

        List<ColumnMeta> missing = TargetTableVerifier.missingColumns(conn, null, "A_B",
                source(col("ID", Types.BIGINT), col("EXTRA", Types.VARCHAR)));

        assertThat(missing).extracting(ColumnMeta::getName).containsExactly("EXTRA");
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
    void wideBitTargetColumnAcceptsAWideBitSource() throws SQLException {
        // docker 实测 MySQL→MySQL(CdcVolumeAndTypesIT.bit16RoundTrip):源与目标是同一份
        // DDL 的 BIT(16),驱动两侧都报 JDBC -7,但源按宽度归数值族、目标只看 JDBC 类型
        // 归布尔族 —— shape check 拒绝了源表自己的形态("source JDBC type -7 vs target
        // -7"),基线全量直接失败。目标的宽度必须和源一样参与判族。
        Connection mysql = metadataReporting("V", Types.BIT, 16);

        String reason = TargetTableVerifier.verify(mysql, null, "T",
                source(bitCol("V", 16)), DatabaseType.MYSQL);

        assertThat(reason).isNull();
    }

    @Test
    void wideBitSourceStillRejectsASingleBitTarget() throws SQLException {
        // 守护:宽度规则是对称判族,不是"BIT 目标一律放行"。目标是 BIT(1)/BOOLEAN 时,
        // 宽 BIT 源(字节数组)仍然塞不进去。
        Connection mysql = metadataReporting("V", Types.BIT, 1);

        String reason = TargetTableVerifier.verify(mysql, null, "T",
                source(bitCol("V", 16)), DatabaseType.MYSQL);

        assertThat(reason).contains("incompatible type");
    }

    @Test
    void singleBitAgainstBooleanTargetPasses() throws SQLException {
        createTarget("CREATE TABLE T (C_FLAG BOOLEAN)");

        String reason = TargetTableVerifier.verify(conn, null, "T",
                source(bitCol("C_FLAG", 1)));

        assertThat(reason).isNull();
    }

    // —— 缺列的「诊断」与「医治」分开：verify 只报告，补列由 StructureSyncService 执行。
    // 这里钉住诊断那一半：目标表有、源表没有的列不动，源表有、目标表没有的列按源顺序列出。 ——

    @Test
    void missingColumnsListsWhatTheTargetLacksInSourceOrder() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT, LEGACY VARCHAR(10))");

        List<ColumnMeta> missing = TargetTableVerifier.missingColumns(conn, null, "T",
                source(col("ID", Types.BIGINT), col("NAME", Types.VARCHAR),
                        col("AMOUNT", Types.DECIMAL)));

        assertThat(missing).extracting(ColumnMeta::getName).containsExactly("NAME", "AMOUNT");
    }

    @Test
    void aCompatibleTargetHasNoMissingColumns() throws SQLException {
        // 名字大小写不同不算缺列：源端 MySQL 报小写、目标 Oracle 存大写，同一列。
        createTarget("CREATE TABLE T (ID BIGINT, NAME VARCHAR(50))");

        List<ColumnMeta> missing = TargetTableVerifier.missingColumns(conn, null, "T",
                source(col("id", Types.BIGINT), col("name", Types.VARCHAR)));

        assertThat(missing).isEmpty();
    }

    // —— upsert 的「值没变就不写」条件能不能挂，由目标列的真实类型决定。
    // 光看调用方手里那份绑定类型不行:源端 VARCHAR 落在目标 CLOB 上是本项目明确支持的形态
    // (见 aClobTargetAcceptsAVarcharSource),而拿 CLOB 去做 = 比较会让整条语句在 Oracle 上
    // 直接报 ORA-22848(docker 实测)。 ——

    @Test
    void aClobColumnCannotBeCompared() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT, NOTE CLOB)");

        assertThat(TargetTableVerifier.uncomparableColumns(conn, null, "T", DatabaseType.ORACLE,
                List.of("ID", "NOTE"))).containsExactly("NOTE");
    }

    @Test
    void aBlobColumnCannotBeCompared() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT, DATA BLOB)");

        assertThat(TargetTableVerifier.uncomparableColumns(conn, null, "T", DatabaseType.MYSQL,
                List.of("ID", "DATA"))).containsExactly("DATA");
    }

    @Test
    void plainColumnsCanAllBeCompared() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT, NAME VARCHAR(50), AMOUNT DECIMAL(10,2),"
                + " SEEN_AT TIMESTAMP, FLAG BOOLEAN)");

        assertThat(TargetTableVerifier.uncomparableColumns(conn, null, "T", DatabaseType.ORACLE,
                List.of("ID", "NAME", "AMOUNT", "SEEN_AT", "FLAG"))).isEmpty();
    }

    @Test
    void anUnknownTypeCannotBeCompared() throws SQLException {
        // PG 的 json/jsonb/uuid 都报 Types.OTHER,其中 json 连 = 运算符都没有;判不了就不放行。
        Connection pg = metadataReporting("C_JSONB", Types.OTHER);

        assertThat(TargetTableVerifier.uncomparableColumns(pg, null, "T", DatabaseType.POSTGRESQL,
                List.of("C_JSONB"))).containsExactly("C_JSONB");
    }

    @Test
    void aColumnTheTargetDoesNotHaveCannotBeCompared() throws SQLException {
        createTarget("CREATE TABLE T (ID BIGINT)");

        assertThat(TargetTableVerifier.uncomparableColumns(conn, null, "T", DatabaseType.ORACLE,
                List.of("ID", "MISSING"))).containsExactly("MISSING");
    }

    @Test
    void theOracleFamilyLookupFoldsTheTableName() throws SQLException {
        // 与 verify 同一条规则:Oracle 家族把未加引号的标识符按大写存,小写请求得能对上。
        createTarget("CREATE TABLE orders (id BIGINT, name VARCHAR(50))");

        assertThat(TargetTableVerifier.uncomparableColumns(conn, null, "orders",
                DatabaseType.ORACLE, List.of("ID", "NAME"))).isEmpty();
    }

    @Test
    void askingAboutNoColumnsVouchesForAllOfThem() throws SQLException {
        assertThat(TargetTableVerifier.uncomparableColumns(conn, null, "T",
                DatabaseType.ORACLE, List.of())).isEmpty();
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

    // —— docker 实测 MySQL→Oracle:Oracle 驱动为它自己的浮点类型报的 DATA_TYPE 是厂商码,
    // 不是 java.sql.Types 里的值 —— BINARY_FLOAT=100,BINARY_DOUBLE=101。而这两个类型正是
    // OracleDialect 自己为源端 FLOAT/DOUBLE 生成的形态,于是建表成功、shape check 却判
    // "incompatible type (source JDBC type 8 vs target 101)",同步在基线阶段就中止。 ——

    @Test
    void oracleBinaryDoubleColumnAcceptsAMysqlDoubleSource() throws SQLException {
        Connection oracle = metadataReporting("C_DOUBLE", 101);

        String reason = TargetTableVerifier.verify(oracle, null, "T",
                source(col("C_DOUBLE", "DOUBLE", Types.DOUBLE)), DatabaseType.ORACLE);

        assertThat(reason).isNull();
    }

    @Test
    void oracleBinaryFloatColumnAcceptsAMysqlFloatSource() throws SQLException {
        Connection oracle = metadataReporting("C_FLOAT", 100);

        String reason = TargetTableVerifier.verify(oracle, null, "T",
                source(col("C_FLOAT", "FLOAT", Types.FLOAT)), DatabaseType.ORACLE);

        assertThat(reason).isNull();
    }

    @Test
    void oracleBinaryDoubleColumnStillRejectsATextSource() throws SQLException {
        // 放行的只是"数值"这一族:文本源塞进 BINARY_DOUBLE 仍然要拦。
        Connection oracle = metadataReporting("C_DOUBLE", 101);

        String reason = TargetTableVerifier.verify(oracle, null, "T",
                source(col("C_DOUBLE", Types.VARCHAR)), DatabaseType.ORACLE);

        assertThat(reason).contains("incompatible type");
    }

    @Test
    void vendorFloatCodesStillNeedAnExactMatchOnNonOracleTargets() throws SQLException {
        // 100/101 只在 Oracle 家族里被解释成浮点数;别的目标上仍是"未知类型须精确匹配"。
        Connection mysql = metadataReporting("C_DOUBLE", 101);

        String reason = TargetTableVerifier.verify(mysql, null, "T",
                source(col("C_DOUBLE", "DOUBLE", Types.DOUBLE)), DatabaseType.MYSQL);

        assertThat(reason).contains("incompatible type");
    }

    /**
     * A connection whose column dictionary reports one column with a vendor DATA_TYPE —
     * the only way to exercise codes H2 cannot create. {@code prepareStatement} throws,
     * which is what the identity pre-check sees on a server without the dictionary view.
     */
    private static Connection metadataReporting(String columnName, int dataType)
            throws SQLException {
        return metadataReporting(columnName, dataType, 0);
    }

    /** As the 2-arg form but also reporting the column's width, the way MySQL reports BIT(n). */
    private static Connection metadataReporting(String columnName, int dataType, int columnSize)
            throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getColumns(any(), any(), any(), any())).thenReturn(columns);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("TABLE_NAME")).thenReturn("T");
        when(columns.getString("COLUMN_NAME")).thenReturn(columnName);
        when(columns.getInt("DATA_TYPE")).thenReturn(dataType);
        when(columns.getInt("COLUMN_SIZE")).thenReturn(columnSize);
        when(connection.prepareStatement(anyString()))
                .thenThrow(new SQLException("ALL_TAB_IDENTITY_COLS not exposed"));
        return connection;
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
