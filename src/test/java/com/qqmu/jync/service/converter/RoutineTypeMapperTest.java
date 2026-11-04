package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * Parameters and function return types come from the source dictionary as vendor spellings —
 * {@code VARCHAR2(100)}, {@code int(11) unsigned}, {@code character varying(100)} — and the
 * routine header builders embed them verbatim, bypassing the {@link AbstractSqlDialect#mapType}
 * machinery table columns go through. These tests pin the cross-family mapping.
 */
class RoutineTypeMapperTest {

    private final RoutineTypeMapper mapper = new RoutineTypeMapper();

    private RoutineTypeMapper.Mapping map(String declaration, DatabaseType from,
                                          SqlDialect to) {
        return mapper.map(declaration, null, from, to);
    }

    private RoutineTypeMapper.Mapping map(String declaration, Integer fallbackSize,
                                          DatabaseType from, SqlDialect to) {
        return mapper.map(declaration, fallbackSize, from, to);
    }

    // --- Character types -------------------------------------------------------------

    @Test
    void oracleVarchar2BecomesMysqlVarcharKeepingTheLength() {
        RoutineTypeMapper.Mapping result = map("VARCHAR2(100)",
                DatabaseType.ORACLE, new MySqlDialect());
        assertThat(result.resolved()).isTrue();
        assertThat(result.typeName()).isEqualTo("VARCHAR(100)");
    }

    @Test
    void aLengthReportedOutsideTheDeclarationIsUsedAsTheWidth() {
        // getProcedureColumns hands back TYPE_NAME = "VARCHAR2" and PRECISION separately.
        RoutineTypeMapper.Mapping result = map("VARCHAR2", 100,
                DatabaseType.ORACLE, new MySqlDialect());
        assertThat(result.typeName()).isEqualTo("VARCHAR(100)");
    }

    @Test
    void postgresCharacterVaryingBecomesMysqlVarchar() {
        RoutineTypeMapper.Mapping result = map("character varying(255)",
                DatabaseType.POSTGRESQL, new MySqlDialect());
        assertThat(result.resolved()).isTrue();
        assertThat(result.typeName()).isEqualTo("VARCHAR(255)");
    }

    @Test
    void oracleCharKeepsItsLength() {
        assertThat(map("CHAR(5)", DatabaseType.ORACLE, new MySqlDialect()).typeName())
                .isEqualTo("CHAR(5)");
    }

    @Test
    void unconstrainedVarcharBecomesTheTargetLargeObjectType() {
        // PG "text" / an unbounded varchar: MySQL cannot size it, so it must become LONGTEXT
        // rather than VARCHAR(0).
        assertThat(map("TEXT", DatabaseType.POSTGRESQL, new MySqlDialect()).typeName())
                .isEqualTo("LONGTEXT");
        assertThat(map("VARCHAR", DatabaseType.POSTGRESQL, new SqlServerDialect()).typeName())
                .isEqualTo("NVARCHAR(MAX)");
    }

    // --- Numeric types ---------------------------------------------------------------

    @Test
    void oracleNumberWithScaleBecomesDecimal() {
        assertThat(map("NUMBER(10,2)", DatabaseType.ORACLE, new MySqlDialect()).typeName())
                .isEqualTo("DECIMAL(10,2)");
    }

    @Test
    void unconstrainedOracleNumberKeepsTheFraction() {
        // No precision is invented here: the shared decimalType maps to the widest decimal with
        // fractional digits so a scale is not silently rounded away.
        assertThat(map("NUMBER", DatabaseType.ORACLE, new PostgresDialect()).typeName())
                .isEqualTo("DECIMAL(38,10)");
    }

    @Test
    void mysqlIntBecomesPostgresInteger() {
        assertThat(map("int(11)", DatabaseType.MYSQL, new PostgresDialect()).typeName())
                .isEqualTo("INTEGER");
    }

    @Test
    void mysqlUnsignedIntWidensToBigintOffTheMysqlFamily() {
        // INT UNSIGNED tops 4.29e9, beyond signed INTEGER.
        assertThat(map("int(10) unsigned", DatabaseType.MYSQL, new PostgresDialect()).typeName())
                .isEqualTo("BIGINT");
    }

    @Test
    void mysqlUnsignedBigintBecomesAnExactDecimal() {
        RoutineTypeMapper.Mapping result = map("bigint(20) unsigned",
                DatabaseType.MYSQL, new PostgresDialect());
        assertThat(result.typeName()).isEqualTo("DECIMAL(20,0)");
    }

    @Test
    void tinyintAndSmallintUnsignedWidenOneBand() {
        assertThat(map("tinyint(3) unsigned", DatabaseType.MYSQL, new PostgresDialect()).typeName())
                .isEqualTo("SMALLINT");
        assertThat(map("smallint(5) unsigned", DatabaseType.MYSQL, new PostgresDialect()).typeName())
                .isEqualTo("INTEGER");
    }

    @Test
    void oracleBinaryFloatStaysSinglePrecision() {
        assertThat(map("BINARY_FLOAT", DatabaseType.ORACLE, new PostgresDialect()).typeName())
                .isEqualTo("REAL");
    }

    // --- Temporal types --------------------------------------------------------------

    @Test
    void oracleDateWidensToTimestampBecauseItCarriesATimeOfDay() {
        assertThat(map("DATE", DatabaseType.ORACLE, new MySqlDialect()).typeName())
                .isEqualTo("DATETIME(6)");
    }

    @Test
    void timestampWithTimeZoneKeepsTheTemporalCategory() {
        RoutineTypeMapper.Mapping result = map("TIMESTAMP(6) WITH TIME ZONE",
                DatabaseType.ORACLE, new PostgresDialect());
        assertThat(result.resolved()).isTrue();
        assertThat(result.typeName()).isEqualTo("TIMESTAMP WITH TIME ZONE");
    }

    @Test
    void mysqlYearBecomesASmallintOffTheMysqlFamily() {
        assertThat(map("YEAR", DatabaseType.MYSQL, new PostgresDialect()).typeName())
                .isEqualTo("SMALLINT");
    }

    // --- Binary types ----------------------------------------------------------------

    @Test
    void oracleRawBecomesVarbinaryAndKeepsItsLength() {
        assertThat(map("RAW(1000)", DatabaseType.ORACLE, new SqlServerDialect()).typeName())
                .isEqualTo("VARBINARY(1000)");
    }

    // --- Special types ---------------------------------------------------------------

    @Test
    void sqlServerUniqueidentifierBecomesVarchar36() {
        assertThat(map("UNIQUEIDENTIFIER", DatabaseType.SQLSERVER, new PostgresDialect()).typeName())
                .isEqualTo("VARCHAR(36)");
    }

    @Test
    void jsonIsPreservedAsTextOnTargetsWithoutTheNativeTypeHere() {
        // PostgresDialect maps JSON like table columns do: its jsonType() degrades to TEXT.
        assertThat(map("JSON", DatabaseType.MYSQL, new PostgresDialect()).typeName())
                .isEqualTo("TEXT");
    }

    @Test
    void anOracleIntervalBecomesVarchar64() {
        assertThat(map("INTERVAL DAY(2) TO SECOND(6)",
                DatabaseType.ORACLE, new PostgresDialect()).typeName())
                .isEqualTo("VARCHAR(64)");
    }

    // --- Same family and unresolvable ------------------------------------------------

    @Test
    void sameFamilyKeepsTheSourceSpellingVerbatim() {
        RoutineTypeMapper.Mapping result = map("int(11)",
                DatabaseType.MYSQL, new MySqlDialect());
        assertThat(result.resolved()).isTrue();
        assertThat(result.typeName()).isEqualTo("int(11)");
    }

    @Test
    void anUnknownCrossFamilyTypeFallsBackToClobAndReportsUnresolved() {
        RoutineTypeMapper.Mapping result = map("SOME_VENDOR_SPECIAL",
                DatabaseType.ORACLE, new MySqlDialect());
        assertThat(result.resolved()).isFalse();
        assertThat(result.typeName()).isEqualTo("LONGTEXT");
    }

    @Test
    void anUnknownBinaryLookingNameFallsBackToBlob() {
        RoutineTypeMapper.Mapping result = map("VENDO_BLOB_SPECIAL",
                DatabaseType.ORACLE, new PostgresDialect());
        assertThat(result.resolved()).isFalse();
        assertThat(result.typeName()).isEqualTo("BYTEA");
    }

    @Test
    void aTableReturningFunctionIsUnresolved() {
        RoutineTypeMapper.Mapping result = map("TABLE(x integer)",
                DatabaseType.POSTGRESQL, new MySqlDialect());
        assertThat(result.resolved()).isFalse();
    }

    @Test
    void aBlankDeclarationIsUnresolved() {
        assertThat(map("  ", DatabaseType.ORACLE, new MySqlDialect()).resolved()).isFalse();
        assertThat(map(null, DatabaseType.ORACLE, new MySqlDialect()).resolved()).isFalse();
    }
}
