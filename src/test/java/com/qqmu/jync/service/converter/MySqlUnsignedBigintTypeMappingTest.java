package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * MySQL {@code BIGINT UNSIGNED} reaches JDBC as {@code Types.BIGINT} with TYPE_NAME
 * "BIGINT UNSIGNED" and COLUMN_SIZE 20 — values up to 2^64-1 overflow a signed BIGINT,
 * so the generated DDL must widen it (the documented target type is NUMERIC/DECIMAL(20)).
 * MySQL-family targets keep the native unsigned type instead.
 */
class MySqlUnsignedBigintTypeMappingTest {

    private static ColumnMeta unsignedBigint() {
        ColumnMeta c = new ColumnMeta();
        c.setName("id");
        c.setJdbcType(Types.BIGINT);
        c.setTypeName("BIGINT UNSIGNED");
        c.setSize(20);
        return c;
    }

    private static ColumnMeta signedBigint() {
        ColumnMeta c = new ColumnMeta();
        c.setName("id");
        c.setJdbcType(Types.BIGINT);
        c.setTypeName("BIGINT");
        c.setSize(19);
        return c;
    }

    @Test
    void unsignedBigintWidensToDecimal20OnNonMysqlTargets() {
        assertThat(new PostgresDialect().mapType(unsignedBigint(), DatabaseType.MYSQL))
                .isEqualTo("DECIMAL(20,0)");
        assertThat(new OpenGaussDialect().mapType(unsignedBigint(), DatabaseType.MYSQL))
                .isEqualTo("DECIMAL(20,0)");
        assertThat(new Db2Dialect().mapType(unsignedBigint(), DatabaseType.MYSQL))
                .isEqualTo("DECIMAL(20,0)");
        assertThat(new SqlServerDialect().mapType(unsignedBigint(), DatabaseType.MYSQL))
                .isEqualTo("DECIMAL(20,0)");
        // Oracle accepts the ANSI DECIMAL keyword (stored as NUMBER(20,0)).
        assertThat(new OracleDialect().mapType(unsignedBigint(), DatabaseType.MYSQL))
                .isEqualTo("DECIMAL(20,0)");
    }

    @Test
    void unsignedBigintStaysNativeOnMysqlFamilyTargets() {
        assertThat(new MySqlDialect().mapType(unsignedBigint(), DatabaseType.MYSQL))
                .isEqualTo("BIGINT UNSIGNED");
    }

    @Test
    void signedBigintIsUnchangedOnEveryTarget() {
        assertThat(new MySqlDialect().mapType(signedBigint(), DatabaseType.MYSQL))
                .isEqualTo("BIGINT");
        assertThat(new PostgresDialect().mapType(signedBigint(), DatabaseType.MYSQL))
                .containsIgnoringCase("BIGINT");
        assertThat(new Db2Dialect().mapType(signedBigint(), DatabaseType.MYSQL))
                .isEqualTo("BIGINT");
    }

    @Test
    void intUnsignedStaysBigintBecauseSignedBigintHoldsItsRange() {
        // Connector/J reports INT UNSIGNED as Types.BIGINT too; widening it to DECIMAL
        // would be a needless type change (a signed BIGINT covers 0..2^32-1 with room).
        ColumnMeta intUnsigned = new ColumnMeta();
        intUnsigned.setName("n");
        intUnsigned.setJdbcType(Types.BIGINT);
        intUnsigned.setTypeName("INT UNSIGNED");
        intUnsigned.setSize(10);

        assertThat(new PostgresDialect().mapType(intUnsigned, DatabaseType.MYSQL))
                .containsIgnoringCase("BIGINT");
        assertThat(new MySqlDialect().mapType(intUnsigned, DatabaseType.MYSQL))
                .isEqualTo("BIGINT");
    }

    @Test
    void bigintFromNonMysqlSourcesIsNeverTreatedAsUnsigned() {
        // A hypothetical source whose type name merely mentions UNSIGNED but is not MySQL
        // family must follow the ordinary BIGINT mapping.
        ColumnMeta c = unsignedBigint();
        assertThat(new PostgresDialect().mapType(c, DatabaseType.POSTGRESQL))
                .containsIgnoringCase("BIGINT");
    }
}
