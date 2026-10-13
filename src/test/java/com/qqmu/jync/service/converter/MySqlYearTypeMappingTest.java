package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * MySQL YEAR is an integer of years (1901-2155), but Connector/J reports it in metadata
 * as JDBC {@link Types#DATE} with type name "YEAR" — a legacy quirk. Naïvely mapping it
 * to a DATE column leaves the target unable to receive the integer values the driver
 * actually returns for the rows (observed against MySQL 8 in the v1.7.1 docker matrix:
 * a hand-built INT target was rejected by the shape check, and a structure-synced DATE
 * target would reject the integer bind). Non-MySQL targets must therefore receive a
 * 2-byte integer; MySQL-family targets keep the native YEAR type.
 */
class MySqlYearTypeMappingTest {

    private final PostgresDialect postgres = new PostgresDialect();
    private final OpenGaussDialect openGauss = new OpenGaussDialect();
    private final MySqlDialect mysql = new MySqlDialect();

    private static ColumnMeta mysqlYear() {
        ColumnMeta c = new ColumnMeta();
        c.setName("c_year");
        c.setTypeName("YEAR");
        c.setJdbcType(Types.DATE);
        c.setSize(4);
        c.setNullable(true);
        c.setOrdinalPosition(1);
        return c;
    }

    private static ColumnMeta mysqlDate() {
        ColumnMeta c = new ColumnMeta();
        c.setName("c_date");
        c.setTypeName("DATE");
        c.setJdbcType(Types.DATE);
        c.setSize(10);
        c.setNullable(true);
        c.setOrdinalPosition(1);
        return c;
    }

    @Test
    void mysqlYearMapsToSmallintOnPostgresTargets() {
        assertThat(postgres.mapType(mysqlYear(), DatabaseType.MYSQL).toUpperCase())
                .contains("SMALLINT");
    }

    @Test
    void mysqlYearMapsToSmallintOnOpenGaussTargets() {
        assertThat(openGauss.mapType(mysqlYear(), DatabaseType.MYSQL).toUpperCase())
                .contains("SMALLINT");
    }

    @Test
    void mysqlYearStaysYearOnMysqlFamilyTargets() {
        assertThat(mysql.mapType(mysqlYear(), DatabaseType.MYSQL).toUpperCase())
                .isEqualTo("YEAR");
    }

    @Test
    void aRealDateColumnStillMapsToDate() {
        assertThat(postgres.mapType(mysqlDate(), DatabaseType.MYSQL).toUpperCase())
                .isEqualTo("DATE");
        assertThat(mysql.mapType(mysqlDate(), DatabaseType.MYSQL).toUpperCase())
                .isEqualTo("DATE");
    }
}
