package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Vendor JDBC codes outside {@link java.sql.Types} used to be passed through by name, so a
 * MySQL→Oracle table with an Oracle {@code BINARY_FLOAT} source column either produced a
 * type the target could not create or had no mapping at all. Every audited vendor code now
 * canonicalizes to a portable type; codes that collide across products (Oracle 100 is
 * BINARY_FLOAT, DB2 100 is ROWID) are resolved by the source product.
 */
class VendorTypeMapTest {

    private static ColumnMeta column(int jdbcType, String typeName) {
        ColumnMeta column = new ColumnMeta();
        column.setJdbcType(jdbcType);
        column.setTypeName(typeName);
        return column;
    }

    @Test
    void oracleVendorCodesCanonicalize() {
        assertThat(VendorTypeMap.canonical(DatabaseType.ORACLE, column(100, "BINARY_FLOAT"))
                .jdbcType()).isEqualTo(Types.REAL);
        assertThat(VendorTypeMap.canonical(DatabaseType.ORACLE, column(101, "BINARY_DOUBLE"))
                .jdbcType()).isEqualTo(Types.DOUBLE);
        assertThat(VendorTypeMap.canonical(DatabaseType.ORACLE, column(-101, "TIMESTAMPTZ"))
                .jdbcType()).isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(VendorTypeMap.canonical(DatabaseType.ORACLE, column(-103, "INTERVALYM"))
                .jdbcType()).isEqualTo(Types.VARCHAR);
        assertThat(VendorTypeMap.canonical(DatabaseType.ORACLE, column(2007, "XMLTYPE"))
                .jdbcType()).isEqualTo(Types.CLOB);
    }

    @Test
    void sqlServerVendorCodesCanonicalize() {
        assertThat(VendorTypeMap.canonical(DatabaseType.SQLSERVER,
                column(-145, "uniqueidentifier")).jdbcType()).isEqualTo(Types.VARCHAR);
        assertThat(VendorTypeMap.canonical(DatabaseType.SQLSERVER,
                column(-148, "money")).jdbcType()).isEqualTo(Types.DECIMAL);
        assertThat(VendorTypeMap.canonical(DatabaseType.SQLSERVER,
                column(-155, "datetimeoffset")).jdbcType())
                .isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(VendorTypeMap.canonical(DatabaseType.SQLSERVER,
                column(-157, "geometry")).jdbcType()).isEqualTo(Types.BLOB);
    }

    @Test
    void db2VendorCodesCanonicalize() {
        assertThat(VendorTypeMap.canonical(DatabaseType.DB2,
                column(-100001, "DECFLOAT")).jdbcType()).isEqualTo(Types.DECIMAL);
        assertThat(VendorTypeMap.canonical(DatabaseType.DB2,
                column(2009, "XML")).jdbcType()).isEqualTo(Types.CLOB);
        assertThat(VendorTypeMap.canonical(DatabaseType.DB2,
                column(100, "ROWID")).jdbcType()).isEqualTo(Types.VARCHAR);
    }

    /** Code 100 means BINARY_FLOAT on Oracle and ROWID on DB2; the product decides. */
    @Test
    void collidingVendorCodesAreScopedBySourceProduct() {
        assertThat(VendorTypeMap.canonical(DatabaseType.ORACLE, column(100, "BINARY_FLOAT"))
                .jdbcType()).isEqualTo(Types.REAL);
        assertThat(VendorTypeMap.canonical(DatabaseType.DB2, column(100, "ROWID"))
                .jdbcType()).isEqualTo(Types.VARCHAR);
    }

    @Test
    void mysqlTypeNamesCanonicalize() {
        assertThat(VendorTypeMap.canonical(DatabaseType.MYSQL, column(Types.CHAR, "ENUM"))
                .jdbcType()).isEqualTo(Types.VARCHAR);
        assertThat(VendorTypeMap.canonical(DatabaseType.MYSQL, column(Types.CHAR, "SET"))
                .jdbcType()).isEqualTo(Types.VARCHAR);
        assertThat(VendorTypeMap.canonical(DatabaseType.MYSQL,
                column(Types.BINARY, "GEOMETRY")).jdbcType()).isEqualTo(Types.BLOB);
    }

    @Test
    void standardTypesAreUnchanged() {
        assertThat(VendorTypeMap.canonical(DatabaseType.MYSQL,
                column(Types.VARCHAR, "VARCHAR")).jdbcType()).isEqualTo(Types.VARCHAR);
    }

    /**
     * 绑定路径原先没有归一化：SQL Server 的 geometry(-157)/sql_variant(-156) 列存在 NULL
     * 时，源端原始 JDBC 码被直接交给 {@code PreparedStatement.setNull}，而 ojdbc 的
     * {@code OracleStatement.getInternalType} 对表外的码一律抛 ORA-17004（Invalid column
     * type），整张表每轮都失败。绑定类型必须先归一化成可移植类型。
     */
    @Test
    void bindTypesCanonicalizeVendorCodes() {
        assertThat(VendorTypeMap.bindType(DatabaseType.SQLSERVER, -157, "geometry"))
                .isEqualTo(Types.BLOB);
        assertThat(VendorTypeMap.bindType(DatabaseType.SQLSERVER, -156, "sql_variant"))
                .isEqualTo(Types.CLOB);
        assertThat(VendorTypeMap.bindType(DatabaseType.SQLSERVER, -155, "datetimeoffset"))
                .isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(VendorTypeMap.bindType(DatabaseType.DB2, -100001, "DECFLOAT"))
                .isEqualTo(Types.DECIMAL);
        assertThat(VendorTypeMap.bindType(DatabaseType.MYSQL, Types.BINARY, "GEOMETRY"))
                .isEqualTo(Types.BLOB);
        // 元数据没带类型名时，产品作用域内的码仍然能定性。
        assertThat(VendorTypeMap.bindType(DatabaseType.SQLSERVER, -157, null))
                .isEqualTo(Types.BLOB);
        // 调用方已经做过的细化必须保留：只改写厂商类型。
        assertThat(VendorTypeMap.bindType(DatabaseType.MYSQL, Types.SMALLINT, "YEAR"))
                .isEqualTo(Types.SMALLINT);
        assertThat(VendorTypeMap.bindType(DatabaseType.MYSQL, Types.VARCHAR, "VARCHAR"))
                .isEqualTo(Types.VARCHAR);
    }

    /**
     * ojdbc8 21.5.0.0 does not report the short names this class was written against:
     * {@code DatabaseMetaData.getColumns} returns "TIMESTAMP(6) WITH TIME ZONE",
     * "TIMESTAMP(6) WITH LOCAL TIME ZONE", "INTERVAL YEAR(2) TO MONTH" and
     * "INTERVAL DAY(2) TO SECOND(6)" (measured on Oracle 23ai Free against a live table).
     * Exact-name matching left all four unknown, and the source side of the target-shape
     * check has no product to scope their codes by, so every such column was filed as
     * incompatible with every target — including the one Jync had just created.
     */
    @Test
    void oraclePrecisionDecoratedNamesKeepTheirFamily() {
        assertThat(VendorTypeMap.family(null, -101, "TIMESTAMP(6) WITH TIME ZONE"))
                .isEqualTo(3);
        assertThat(VendorTypeMap.family(null, -102, "TIMESTAMP(6) WITH LOCAL TIME ZONE"))
                .isEqualTo(3);
        assertThat(VendorTypeMap.family(null, -103, "INTERVAL YEAR(2) TO MONTH")).isEqualTo(1);
        assertThat(VendorTypeMap.family(null, -104, "INTERVAL DAY(2) TO SECOND(6)"))
                .isEqualTo(1);
        // Canonicalization must understand the same spellings: it is what maps the DDL.
        assertThat(VendorTypeMap.canonical(DatabaseType.ORACLE,
                column(-101, "TIMESTAMP(6) WITH TIME ZONE")).jdbcType())
                .isEqualTo(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThat(VendorTypeMap.canonical(DatabaseType.ORACLE,
                column(-103, "INTERVAL YEAR(2) TO MONTH")).jdbcType())
                .isEqualTo(Types.VARCHAR);
    }

    @Test
    void familiesCoverVendorCodes() {
        assertThat(VendorTypeMap.family(DatabaseType.ORACLE, 100, "BINARY_FLOAT")).isEqualTo(2);
        assertThat(VendorTypeMap.family(DatabaseType.ORACLE, -101, "TIMESTAMPTZ")).isEqualTo(3);
        assertThat(VendorTypeMap.family(DatabaseType.SQLSERVER, -145, "uniqueidentifier"))
                .isEqualTo(1);
        assertThat(VendorTypeMap.family(DatabaseType.SQLSERVER, -157, "geometry")).isEqualTo(4);
        assertThat(VendorTypeMap.family(DatabaseType.DB2, 100, "ROWID")).isEqualTo(1);
        assertThat(VendorTypeMap.family(DatabaseType.MYSQL, Types.VARCHAR, "JSON")).isEqualTo(1);
        assertThat(VendorTypeMap.family(null, 999, "MYSTERY")).isEqualTo(1099);
    }
}
