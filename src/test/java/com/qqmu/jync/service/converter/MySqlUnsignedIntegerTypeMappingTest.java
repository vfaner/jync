package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * MySQL's unsigned integer types reach JDBC with their signed width: {@code TINYINT UNSIGNED}
 * is {@code Types.TINYINT} (0..255), {@code SMALLINT UNSIGNED} is {@code Types.SMALLINT}
 * (0..65535) and {@code INT UNSIGNED} is {@code Types.INTEGER} (0..2^32-1). Mapping them by
 * JDBC code alone emitted a signed column with half the range, so blue values failed at insert
 * time; a MySQL-family target even lost the UNSIGNED attribute itself.
 */
class MySqlUnsignedIntegerTypeMappingTest {

    private static ColumnMeta unsigned(int jdbcType, String typeName, int size) {
        ColumnMeta c = new ColumnMeta();
        c.setName("n");
        c.setJdbcType(jdbcType);
        c.setTypeName(typeName);
        c.setSize(size);
        return c;
    }

    @Test
    void unsignedTinyintWidensToSmallintOutsideTheMysqlFamily() {
        ColumnMeta c = unsigned(Types.TINYINT, "TINYINT UNSIGNED", 3);
        assertThat(new PostgresDialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("SMALLINT");
        assertThat(new Db2Dialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("SMALLINT");
        assertThat(new SqlServerDialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("SMALLINT");
        assertThat(new HanaSqlDialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("SMALLINT");
        assertThat(new OracleDialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("NUMBER(5)");
    }

    @Test
    void unsignedSmallintWidensToIntOutsideTheMysqlFamily() {
        ColumnMeta c = unsigned(Types.SMALLINT, "SMALLINT UNSIGNED", 5);
        assertThat(new PostgresDialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("INTEGER");
        assertThat(new Db2Dialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("INTEGER");
        assertThat(new SqlServerDialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("INT");
        assertThat(new HanaSqlDialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("INTEGER");
        assertThat(new OracleDialect().mapType(c, DatabaseType.MYSQL)).isEqualTo("NUMBER(10)");
    }

    @Test
    void unsignedIntAndMediumintWidenToBigintOutsideTheMysqlFamily() {
        ColumnMeta intUnsigned = unsigned(Types.INTEGER, "INT UNSIGNED", 10);
        assertThat(new PostgresDialect().mapType(intUnsigned, DatabaseType.MYSQL))
                .isEqualTo("BIGINT");
        assertThat(new Db2Dialect().mapType(intUnsigned, DatabaseType.MYSQL))
                .isEqualTo("BIGINT");
        assertThat(new SqlServerDialect().mapType(intUnsigned, DatabaseType.MYSQL))
                .isEqualTo("BIGINT");
        assertThat(new HanaSqlDialect().mapType(intUnsigned, DatabaseType.MYSQL))
                .isEqualTo("BIGINT");
        assertThat(new OracleDialect().mapType(intUnsigned, DatabaseType.MYSQL))
                .isEqualTo("NUMBER(19)");

        ColumnMeta mediumUnsigned = unsigned(Types.INTEGER, "MEDIUMINT UNSIGNED", 7);
        assertThat(new PostgresDialect().mapType(mediumUnsigned, DatabaseType.MYSQL))
                .isEqualTo("BIGINT");
    }

    @Test
    void mysqlFamilyTargetsKeepTheNativeUnsignedSpelling() {
        assertThat(new MySqlDialect().mapType(
                unsigned(Types.TINYINT, "TINYINT UNSIGNED", 3), DatabaseType.MYSQL))
                .isEqualTo("TINYINT UNSIGNED");
        assertThat(new MySqlDialect().mapType(
                unsigned(Types.SMALLINT, "SMALLINT UNSIGNED", 5), DatabaseType.MYSQL))
                .isEqualTo("SMALLINT UNSIGNED");
        assertThat(new MySqlDialect().mapType(
                unsigned(Types.INTEGER, "INT UNSIGNED", 10), DatabaseType.MYSQL))
                .isEqualTo("INT UNSIGNED");
        assertThat(new MySqlDialect().mapType(
                unsigned(Types.INTEGER, "MEDIUMINT UNSIGNED", 7), DatabaseType.MYSQL))
                .isEqualTo("MEDIUMINT UNSIGNED");
    }

    @Test
    void signedIntegersAreUnchanged() {
        ColumnMeta tiny = unsigned(Types.TINYINT, "TINYINT", 3);
        tiny.setTypeName("TINYINT");
        assertThat(new PostgresDialect().mapType(tiny, DatabaseType.MYSQL)).isEqualTo("SMALLINT");
        ColumnMeta intSigned = unsigned(Types.INTEGER, "INT", 10);
        assertThat(new PostgresDialect().mapType(intSigned, DatabaseType.MYSQL))
                .isEqualTo("INTEGER");
        assertThat(new MySqlDialect().mapType(intSigned, DatabaseType.MYSQL)).isEqualTo("INT");
    }

    @Test
    void unsignedNamesFromOtherFamiliesAreNotTreatedAsMysqlUnsigned() {
        ColumnMeta c = unsigned(Types.INTEGER, "INT UNSIGNED", 10);
        // A hypothetical non-MySQL source: the ordinary JDBC mapping applies.
        assertThat(new PostgresDialect().mapType(c, DatabaseType.POSTGRESQL))
                .isEqualTo("INTEGER");
    }
}
