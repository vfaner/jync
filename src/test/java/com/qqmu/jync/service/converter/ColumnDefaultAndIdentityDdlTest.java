package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Column-feature rules that only fire on a specific target product:
 *
 * <ul>
 *   <li>T1: MySQL accepts AUTO_INCREMENT only on integer types; a DECIMAL/NUMBER identity
 *       from Oracle or SQL Server must be forced onto an integer band or CREATE fails with
 *       ER_WRONG_FIELD_SPEC (1063).</li>
 *   <li>T2: MySQL allows CURRENT_TIMESTAMP defaults only on TIMESTAMP/DATETIME; a DATE
 *       column carrying SYSDATE/GETDATE() must become an expression CURRENT_DATE default
 *       or CREATE fails with ER_INVALID_DEFAULT (1067).</li>
 *   <li>T3: MySQL's zero date ('0000-00-00') is not a date any other product accepts; on a
 *       temporal column the default is dropped rather than failing the whole CREATE.</li>
 *   <li>T5: a DATETIME2/DATETIMEOFFSET default on SQL Server must use SYSDATETIME() family
 *       functions; GETDATE() silently rounds the value to 1/300-second datetime precision.</li>
 * </ul>
 */
class ColumnDefaultAndIdentityDdlTest {

    private final MySqlDialect mysql = new MySqlDialect();
    private final OracleDialect oracle = new OracleDialect();
    private final PostgresDialect postgres = new PostgresDialect();
    private final SqlServerDialect sqlServer = new SqlServerDialect();
    private final HanaSqlDialect hana = new HanaSqlDialect();

    private ColumnMeta column(String name, int jdbcType, String typeName,
                              Integer size, Integer scale) {
        ColumnMeta c = new ColumnMeta();
        c.setName(name);
        c.setJdbcType(jdbcType);
        c.setTypeName(typeName);
        if (size != null) {
            c.setSize(size);
        }
        if (scale != null) {
            c.setDecimalDigits(scale);
        }
        return c;
    }

    // --- T1: decimal identity must become an integer AUTO_INCREMENT on MySQL -------------

    @Test
    void oracleNumberIdentityBecomesBigintAutoIncrementOnMySql() {
        ColumnMeta id = column("ID", Types.NUMERIC, "NUMBER", null, null);
        id.setAutoIncrement(true);

        String ddl = mysql.columnDefinition(id, DatabaseType.ORACLE);

        assertThat(ddl).startsWith("`ID` BIGINT AUTO_INCREMENT");
        assertThat(ddl).doesNotContain("DECIMAL").doesNotContain("NUMBER");
    }

    @Test
    void decimalIdentityLandsOnTheSmallestIntegerBandThatCoversItsPrecision() {
        ColumnMeta two = column("ID", Types.DECIMAL, "DECIMAL", 2, 0);
        two.setAutoIncrement(true);
        ColumnMeta four = column("ID", Types.DECIMAL, "DECIMAL", 4, 0);
        four.setAutoIncrement(true);
        ColumnMeta nine = column("ID", Types.DECIMAL, "DECIMAL", 9, 0);
        nine.setAutoIncrement(true);
        ColumnMeta eighteen = column("ID", Types.DECIMAL, "DECIMAL", 18, 0);
        eighteen.setAutoIncrement(true);

        assertThat(mysql.columnDefinition(two, DatabaseType.SQLSERVER)).contains("TINYINT");
        assertThat(mysql.columnDefinition(four, DatabaseType.SQLSERVER)).contains("SMALLINT");
        assertThat(mysql.columnDefinition(nine, DatabaseType.POSTGRESQL)).contains(" INT ");
        assertThat(mysql.columnDefinition(eighteen, DatabaseType.SQLSERVER)).contains("BIGINT");
    }

    @Test
    void aNonAutoIncrementDecimalStaysDecimal() {
        ColumnMeta amount = column("AMOUNT", Types.DECIMAL, "DECIMAL", 18, 2);

        assertThat(mysql.columnDefinition(amount, DatabaseType.ORACLE))
                .startsWith("`AMOUNT` DECIMAL(18,2)");
    }

    // T1 on PostgreSQL: the server rejects "identity column type must be smallint, integer,
    // or bigint", so a decimal identity is lifted onto SERIAL/BIGSERIAL by precision band.

    @Test
    void postgresLiftsDecimalIdentityOntoSerialOrBigserial() {
        ColumnMeta four = column("ID", Types.DECIMAL, "DECIMAL", 4, 0);
        four.setAutoIncrement(true);
        ColumnMeta eighteen = column("ID", Types.DECIMAL, "DECIMAL", 18, 2);
        eighteen.setAutoIncrement(true);

        String fourDdl = postgres.columnDefinition(four, DatabaseType.SQLSERVER);
        assertThat(fourDdl).contains("SERIAL").doesNotContain("BIGSERIAL")
                .doesNotContain("DECIMAL");
        assertThat(postgres.columnDefinition(eighteen, DatabaseType.SQLSERVER))
                .contains("BIGSERIAL");
    }

    @Test
    void postgresKeepsBigintIdentityAsBigserial() {
        ColumnMeta id = column("ID", Types.BIGINT, "BIGINT", null, null);
        id.setAutoIncrement(true);

        assertThat(postgres.columnDefinition(id, DatabaseType.MYSQL)).contains("BIGSERIAL");
    }

    // HANA documents identity columns on exact numeric types with scale zero; a positive
    // scale is not an identity type and is lifted onto an integer band.

    @Test
    void hanaDecimalIdentityWithPositiveScaleLandsOnAnIntegerBand() {
        ColumnMeta two = column("ID", Types.DECIMAL, "DECIMAL", 2, 2);
        two.setAutoIncrement(true);
        ColumnMeta eighteen = column("ID", Types.DECIMAL, "DECIMAL", 18, 2);
        eighteen.setAutoIncrement(true);

        assertThat(hana.columnDefinition(two, DatabaseType.POSTGRESQL))
                .contains("TINYINT GENERATED BY DEFAULT AS IDENTITY")
                .doesNotContain("DECIMAL");
        assertThat(hana.columnDefinition(eighteen, DatabaseType.POSTGRESQL))
                .contains("BIGINT GENERATED BY DEFAULT AS IDENTITY")
                .doesNotContain("DECIMAL");
    }

    @Test
    void hanaDecimalIdentityWithScaleZeroStaysDecimalPerTheDocumentedRule() {
        ColumnMeta id = column("ID", Types.DECIMAL, "DECIMAL", 18, 0);
        id.setAutoIncrement(true);

        assertThat(hana.columnDefinition(id, DatabaseType.POSTGRESQL))
                .contains("DECIMAL(18,0) GENERATED BY DEFAULT AS IDENTITY");
    }

    @Test
    void hanaNonAutoIncrementDecimalWithScaleStaysDecimal() {
        ColumnMeta amount = column("AMOUNT", Types.DECIMAL, "DECIMAL", 18, 2);

        assertThat(hana.columnDefinition(amount, DatabaseType.POSTGRESQL))
                .startsWith("\"AMOUNT\" DECIMAL(18,2)");
    }

    // --- T2: DATE + current-time default becomes (CURRENT_DATE) on MySQL -----------------

    @Test
    void oracleDateWithSysdateMapsToDatetimeSinceOracleDateCarriesTime() {
        // Oracle DATE includes hours/minutes/seconds, so the column itself becomes DATETIME(6)
        // and SYSDATE keeps its CURRENT_TIMESTAMP default: valid, with no precision lost.
        ColumnMeta d = column("D", Types.DATE, "DATE", null, null);
        d.setDefaultValue("SYSDATE");

        String ddl = mysql.columnDefinition(d, DatabaseType.ORACLE);

        assertThat(ddl).contains("DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)");
    }

    @Test
    void postgresDateWithCurrentTimestampDefaultBecomesExpressionCurrentDate() {
        ColumnMeta d = column("D", Types.DATE, "DATE", null, null);
        d.setDefaultValue("CURRENT_TIMESTAMP");

        String ddl = mysql.columnDefinition(d, DatabaseType.POSTGRESQL);

        assertThat(ddl).contains("DATE DEFAULT (CURRENT_DATE)");
        assertThat(ddl).doesNotContain("CURRENT_TIMESTAMP");
    }

    @Test
    void dateColumnWithGetdateDefaultBecomesAnExpressionCurrentDate() {
        ColumnMeta d = column("D", Types.DATE, "DATE", null, null);
        d.setDefaultValue("GETDATE()");

        String ddl = mysql.columnDefinition(d, DatabaseType.SQLSERVER);

        assertThat(ddl).contains("DATE DEFAULT (CURRENT_DATE)");
    }

    @Test
    void datetimeColumnKeepsTheCurrentTimestampDefault() {
        ColumnMeta ts = column("TS", Types.TIMESTAMP, "TIMESTAMP", null, null);
        ts.setDefaultValue("SYSDATE");

        String ddl = mysql.columnDefinition(ts, DatabaseType.ORACLE);

        assertThat(ddl).contains("DATETIME").contains("DEFAULT CURRENT_TIMESTAMP(6)");
    }

    // --- T3: zero-date defaults ----------------------------------------------------------

    @Test
    void zeroDateDefaultOnATemporalColumnIsDroppedForNonMySqlTargets() {
        ColumnMeta d = column("D", Types.DATE, "DATE", null, null);
        d.setDefaultValue("'0000-00-00'");
        ColumnMeta ts = column("TS", Types.TIMESTAMP, "DATETIME", null, null);
        ts.setDefaultValue("'0000-00-00 00:00:00'");

        for (AbstractSqlDialect dialect : new AbstractSqlDialect[]{oracle, postgres, sqlServer}) {
            assertThat(dialect.columnDefinition(d, DatabaseType.MYSQL))
                    .as("zero date on %s", dialect.family())
                    .doesNotContain("DEFAULT");
            assertThat(dialect.columnDefinition(ts, DatabaseType.MYSQL))
                    .as("zero timestamp on %s", dialect.family())
                    .doesNotContain("0000-00-00");
        }
    }

    @Test
    void zeroDateStringOnAVarcharColumnIsKeptAsAnOrdinaryLiteral() {
        ColumnMeta note = column("NOTE", Types.VARCHAR, "VARCHAR", 20, null);
        note.setDefaultValue("'0000-00-00'");

        assertThat(postgres.columnDefinition(note, DatabaseType.MYSQL))
                .contains("DEFAULT '0000-00-00'");
    }

    @Test
    void zeroDateDefaultStaysOnAForMySqlTarget() {
        ColumnMeta d = column("D", Types.DATE, "DATE", null, null);
        d.setDefaultValue("'0000-00-00'");

        assertThat(mysql.columnDefinition(d, DatabaseType.MYSQL))
                .contains("DEFAULT '0000-00-00'");
    }

    // --- T5: DATETIME2 defaults need SYSDATETIME() on SQL Server -------------------------

    @Test
    void datetime2DefaultUsesSysdatetimeNotGetdate() {
        ColumnMeta ts = column("TS", Types.TIMESTAMP, "TIMESTAMP", null, null);
        ts.setDefaultValue("CURRENT_TIMESTAMP");

        String ddl = sqlServer.columnDefinition(ts, DatabaseType.ORACLE);

        assertThat(ddl).contains("DATETIME2").contains("DEFAULT SYSDATETIME()");
        assertThat(ddl).doesNotContain("GETDATE()");
    }

    @Test
    void datetimeoffsetDefaultUsesSysdatetimeoffset() {
        ColumnMeta ts = column("TS", Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMPTZ", null, null);
        ts.setDefaultValue("CURRENT_TIMESTAMP");

        String ddl = sqlServer.columnDefinition(ts, DatabaseType.POSTGRESQL);

        assertThat(ddl).contains("DATETIMEOFFSET").contains("DEFAULT SYSDATETIMEOFFSET()");
    }

    @Test
    void aLiteralDatetime2DefaultIsNotReplacedBySysdatetime() {
        // Only the portable CURRENT_TIMESTAMP render may be upgraded to the DATETIME2-precision
        // function. A declared constant such as '1970-01-01 00:00:00' is not "now": replacing it
        // with SYSDATETIME() silently changes every row inserted without the column.
        ColumnMeta ts = column("TS", Types.TIMESTAMP, "DATETIME", null, null);
        ts.setDefaultValue("'1970-01-01 00:00:00'");

        String ddl = sqlServer.columnDefinition(ts, DatabaseType.MYSQL);

        assertThat(ddl).contains("DEFAULT '1970-01-01 00:00:00'");
        assertThat(ddl).doesNotContain("SYSDATETIME()");
    }

    @Test
    void aLiteralDatetimeoffsetDefaultIsNotReplacedBySysdatetimeoffset() {
        ColumnMeta ts = column("TS", Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMPTZ", 6, null);
        ts.setDefaultValue("'1970-01-01 00:00:00'");

        String ddl = sqlServer.columnDefinition(ts, DatabaseType.POSTGRESQL);

        assertThat(ddl).contains("DEFAULT '1970-01-01 00:00:00'");
        assertThat(ddl).doesNotContain("SYSDATETIMEOFFSET()");
    }
}
