package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Cross-family DDL for product-specific types.
 *
 * <p>Before v1.7.8 an unrecognized vendor type reached {@code mapType}'s fallback, which
 * passed the source spelling through: Oracle {@code XMLTYPE} became a MySQL column of type
 * {@code XMLTYPE}, which the target rejects, and an unknown code from a different product
 * family could produce a name no target knows. Cross-family fallbacks are now always a
 * portable type, while a same-family passthrough still keeps the target's own spelling.
 */
class SqlDialectVendorMappingTest {

    private static ColumnMeta column(int jdbcType, String typeName) {
        ColumnMeta column = new ColumnMeta();
        column.setName("c");
        column.setJdbcType(jdbcType);
        column.setTypeName(typeName);
        return column;
    }

    @Test
    void oracleTargetMapsXmlTypeToClob() {
        assertThat(new OracleDialect().mapType(column(2007, "XMLTYPE"), DatabaseType.ORACLE))
                .isEqualTo("CLOB");
    }

    @Test
    void oracleTargetMapsIntervalsToVarcharAndRowidToVarchar() {
        OracleDialect dialect = new OracleDialect();
        assertThat(dialect.mapType(column(-103, "INTERVALYM"), DatabaseType.ORACLE))
                .isEqualTo("VARCHAR2(64 CHAR)");
        assertThat(dialect.mapType(column(Types.ROWID, "ROWID"), DatabaseType.ORACLE))
                .isEqualTo("VARCHAR2(40 CHAR)");
    }

    @Test
    void oracleTargetKeepsNativeBinaryFloatForOracleSource() {
        assertThat(new OracleDialect().mapType(column(100, "BINARY_FLOAT"), DatabaseType.ORACLE))
                .isEqualTo("BINARY_FLOAT");
    }

    @Test
    void mysqlTargetMapsOracleBinaryFloatToSinglePrecisionFloat() {
        // BINARY_FLOAT is IEEE single precision; widening it to DOUBLE would change the
        // declared target type for no gain.
        assertThat(new MySqlDialect().mapType(column(100, "BINARY_FLOAT"), DatabaseType.ORACLE))
                .isEqualTo("FLOAT");
    }

    @Test
    void sqlServerTargetMapsOracleXmlTypeToNvarcharMax() {
        assertThat(new SqlServerDialect().mapType(column(2007, "XMLTYPE"), DatabaseType.ORACLE))
                .isEqualTo("NVARCHAR(MAX)");
    }

    @Test
    void sqlServerTargetKeepsItsOwnUniqueIdentifier() {
        assertThat(new SqlServerDialect()
                .mapType(column(-145, "uniqueidentifier"), DatabaseType.SQLSERVER))
                .isEqualTo("UNIQUEIDENTIFIER");
    }

    @Test
    void mysqlTargetKeepsNativeJson() {
        assertThat(new MySqlDialect().mapType(column(Types.CHAR, "JSON"), DatabaseType.MYSQL))
                .isEqualTo("JSON");
    }

    @Test
    void postgresTargetStoresMysqlGeometryAsBytea() {
        assertThat(new PostgresDialect().mapType(column(Types.BINARY, "GEOMETRY"),
                DatabaseType.MYSQL)).isEqualTo("BYTEA");
    }

    @Test
    void db2TargetMapsDecfloatToDecimal() {
        String mapped = new Db2Dialect().mapType(column(-100001, "DECFLOAT"), DatabaseType.DB2);
        assertThat(mapped).startsWith("DECIMAL");
        assertThat(mapped).doesNotContainIgnoringCase("DECFLOAT");
    }

    /** A code no product table knows still must not name an invalid cross-family type. */
    @Test
    void unknownVendorTypeCrossFamilyFallsBackToPortable() {
        assertThat(new MySqlDialect().mapType(column(9999, "MYSTERY"), DatabaseType.ORACLE))
                .isEqualTo("LONGTEXT");
        assertThat(new MySqlDialect().mapType(column(9999, "MYSTERY_BLOB"),
                DatabaseType.ORACLE)).isEqualTo("LONGBLOB");
    }

    /** Inside one product family the target's own dictionary understands the raw spelling. */
    @Test
    void unknownTypeNameInsideTheSameFamilyPassesThrough() {
        assertThat(new MySqlDialect().mapType(column(9999, "MYSTERY"), DatabaseType.MYSQL))
                .isEqualTo("MYSTERY");
    }
}
