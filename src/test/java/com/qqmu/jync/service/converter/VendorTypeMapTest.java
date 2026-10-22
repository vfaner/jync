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
