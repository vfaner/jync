package com.synctool.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.synctool.dto.meta.ColumnMeta;
import com.synctool.dto.meta.TableMeta;
import com.synctool.model.DatabaseType;

/**
 * Verifies the properties the sync engine actually relies on, rather than exact SQL strings:
 * that every dialect produces an idempotent upsert, and that type mapping stays inside each
 * product's real limits.
 */
class SqlDialectTest {

    private final MySqlDialect mysql = new MySqlDialect();
    private final OracleDialect oracle = new OracleDialect();
    private final PostgresDialect postgres = new PostgresDialect();
    private final SqlServerDialect sqlServer = new SqlServerDialect();
    private final Db2Dialect db2 = new Db2Dialect();

    private final List<SqlDialect> allDialects =
            List.of(mysql, oracle, postgres, sqlServer, db2, new GenericSqlDialect());

    private static ColumnMeta column(String name, String typeName, int jdbcType, Integer size) {
        ColumnMeta c = new ColumnMeta();
        c.setName(name);
        c.setTypeName(typeName);
        c.setJdbcType(jdbcType);
        c.setSize(size);
        c.setNullable(true);
        c.setOrdinalPosition(1);
        return c;
    }

    // --- Upsert idempotency ------------------------------------------------------------

    /**
     * The engine advances a table's cursor only after a committed target write, so the same
     * row can legitimately be presented twice. Every dialect with a primary key must therefore
     * emit a statement that converges rather than failing on the second application.
     */
    @Test
    void everyDialectProducesAConflictHandlingUpsertWhenAPrimaryKeyExists() {
        List<String> columns = List.of("id", "name", "amount");
        List<String> pk = List.of("id");

        for (SqlDialect dialect : allDialects) {
            String sql = dialect.getUpsertSql("s", "orders", columns, pk).toUpperCase();
            boolean handlesConflict = sql.contains("ON DUPLICATE KEY")
                    || sql.contains("ON CONFLICT")
                    || sql.contains("MERGE");
            if (dialect instanceof GenericSqlDialect) {
                // The generic dialect cannot express one; it declares that so the engine
                // switches to update-then-insert instead of assuming idempotency.
                assertThat(((GenericSqlDialect) dialect).supportsNativeUpsert())
                        .as("generic dialect must declare that it has no native upsert")
                        .isFalse();
            } else {
                assertThat(handlesConflict)
                        .as("%s must emit a conflict-handling upsert", dialect.getClass().getSimpleName())
                        .isTrue();
            }
        }
    }

    @Test
    void upsertBindOrderMatchesPlaceholderCount() {
        List<String> columns = List.of("id", "name", "amount");
        List<String> pk = List.of("id");

        for (SqlDialect dialect : allDialects) {
            String sql = dialect.getUpsertSql("s", "orders", columns, pk);
            long placeholders = sql.chars().filter(ch -> ch == '?').count();
            List<String> bindOrder = dialect.upsertBindOrder(columns, pk);
            // A mismatch here would throw at bind time on every single row.
            assertThat((long) bindOrder.size())
                    .as("%s bind order must match its placeholder count",
                            dialect.getClass().getSimpleName())
                    .isEqualTo(placeholders);
        }
    }

    @Test
    void keyOnlyTableStillProducesAnIdempotentStatement() {
        // A table whose every column is part of the key has nothing to UPDATE; the statement
        // must still not fail when the row already exists.
        List<String> columns = List.of("id");
        List<String> pk = List.of("id");

        String mysqlSql = mysql.getUpsertSql("s", "t", columns, pk).toUpperCase();
        assertThat(mysqlSql).contains("ON DUPLICATE KEY UPDATE");

        String pgSql = postgres.getUpsertSql("s", "t", columns, pk).toUpperCase();
        assertThat(pgSql).contains("DO NOTHING");
    }

    @Test
    void compositePrimaryKeyIsFullyRepresentedInTheConflictPredicate() {
        List<String> columns = List.of("tenant_id", "order_id", "total");
        List<String> pk = List.of("tenant_id", "order_id");

        String pgSql = postgres.getUpsertSql("s", "orders", columns, pk);
        assertThat(pgSql).contains("\"tenant_id\"").contains("\"order_id\"");
        // The non-key column is the only thing that should be updated.
        assertThat(pgSql).contains("EXCLUDED.\"total\"");

        String oracleSql = oracle.getUpsertSql("s", "orders", columns, pk).toUpperCase();
        assertThat(oracleSql).contains("TENANT_ID").contains("ORDER_ID");
    }

    @Test
    void upsertWithoutAPrimaryKeyFallsBackToPlainInsert() {
        // No key means no way to identify an existing row; the engine warns and inserts.
        String sql = mysql.getUpsertSql("s", "log", List.of("a", "b"), List.of()).toUpperCase();
        assertThat(sql).startsWith("INSERT INTO").doesNotContain("ON DUPLICATE");
    }

    // --- Type mapping -----------------------------------------------------------------

    @Test
    void oracleVarcharBeyondItsLimitBecomesAClob() {
        // VARCHAR2 caps at 4000 bytes; a wider source column must not produce invalid DDL.
        ColumnMeta wide = column("body", "VARCHAR", Types.VARCHAR, 40000);
        assertThat(oracle.mapType(wide, DatabaseType.MYSQL)).isEqualTo("CLOB");

        ColumnMeta narrow = column("code", "VARCHAR", Types.VARCHAR, 50);
        assertThat(oracle.mapType(narrow, DatabaseType.MYSQL)).isEqualTo("VARCHAR2(50)");
    }

    @Test
    void sqlServerVarcharBeyondItsLimitBecomesMax() {
        ColumnMeta wide = column("body", "TEXT", Types.LONGVARCHAR, 100000);
        assertThat(sqlServer.mapType(wide, DatabaseType.MYSQL)).isEqualTo("NVARCHAR(MAX)");
    }

    @Test
    void booleanMapsToEachProductsRepresentation() {
        ColumnMeta flag = column("active", "BOOLEAN", Types.BOOLEAN, 1);
        assertThat(postgres.mapType(flag, DatabaseType.MYSQL)).isEqualTo("BOOLEAN");
        // Oracle had no BOOLEAN column type before 23c.
        assertThat(oracle.mapType(flag, DatabaseType.MYSQL)).isEqualTo("NUMBER(1)");
        assertThat(sqlServer.mapType(flag, DatabaseType.MYSQL)).isEqualTo("BIT");
    }

    @Test
    void timestampMapsToTheWidestAvailableTypePerProduct() {
        ColumnMeta ts = column("created", "TIMESTAMP", Types.TIMESTAMP, 26);
        assertThat(mysql.mapType(ts, DatabaseType.POSTGRESQL)).isEqualTo("DATETIME");
        // DATETIME2 has wider range and precision than DATETIME.
        assertThat(sqlServer.mapType(ts, DatabaseType.POSTGRESQL)).isEqualTo("DATETIME2");
        assertThat(oracle.mapType(ts, DatabaseType.POSTGRESQL)).isEqualTo("TIMESTAMP");
    }

    // --- Identifier quoting -----------------------------------------------------------

    @Test
    void identifiersAreQuotedWithEachProductsDelimiter() {
        assertThat(mysql.quoteIdentifier("order")).isEqualTo("`order`");
        assertThat(postgres.quoteIdentifier("order")).isEqualTo("\"order\"");
        assertThat(sqlServer.quoteIdentifier("order")).isEqualTo("[order]");
    }

    @Test
    void alreadyQuotedIdentifiersAreNotDoubleQuoted() {
        // A name arriving with quotes already attached must not become ``order``.
        assertThat(mysql.quoteIdentifier("`order`")).isEqualTo("`order`");
        assertThat(sqlServer.quoteIdentifier("[order]")).isEqualTo("[order]");
    }

    // --- CREATE TABLE ------------------------------------------------------------------

    @Test
    void createTableIncludesThePrimaryKeyAndEveryColumn() {
        TableMeta table = new TableMeta();
        table.setName("orders");
        ColumnMeta id = column("id", "INT", Types.INTEGER, 10);
        id.setNullable(false);
        id.setPrimaryKey(true);
        ColumnMeta name = column("name", "VARCHAR", Types.VARCHAR, 100);
        table.setColumns(List.of(id, name));
        table.setPrimaryKeys(List.of("id"));

        for (SqlDialect dialect : allDialects) {
            String ddl = dialect.getCreateTableSql(table, "s", "orders", DatabaseType.MYSQL);
            assertThat(ddl).as("%s DDL", dialect.getClass().getSimpleName())
                    .contains("CREATE TABLE")
                    .contains("PRIMARY KEY")
                    .containsIgnoringCase("id")
                    .containsIgnoringCase("name")
                    .contains("NOT NULL");
        }
    }

    @Test
    void autoIncrementProducesTheProductsIdentityForm() {
        TableMeta table = new TableMeta();
        table.setName("t");
        ColumnMeta id = column("id", "BIGINT", Types.BIGINT, 19);
        id.setNullable(false);
        id.setAutoIncrement(true);
        id.setPrimaryKey(true);
        table.setColumns(List.of(id));
        table.setPrimaryKeys(List.of("id"));

        assertThat(mysql.getCreateTableSql(table, null, "t", DatabaseType.MYSQL))
                .contains("AUTO_INCREMENT");
        assertThat(postgres.getCreateTableSql(table, null, "t", DatabaseType.MYSQL))
                .contains("BIGSERIAL");
        assertThat(sqlServer.getCreateTableSql(table, null, "t", DatabaseType.MYSQL))
                .contains("IDENTITY");
    }

    @Test
    void nonPortableDefaultsAreDroppedRatherThanEmittedInvalid() {
        // A product-specific default expression would make CREATE TABLE fail outright, which
        // is worse than losing the default.
        ColumnMeta col = column("val", "VARCHAR", Types.VARCHAR, 50);
        col.setDefaultValue("nextval('some_seq'::regclass)");
        assertThat(mysql.renderDefaultValue(col)).isNull();

        // A current-timestamp default is portable and should be translated, not dropped.
        ColumnMeta ts = column("created", "TIMESTAMP", Types.TIMESTAMP, 26);
        ts.setDefaultValue("CURRENT_TIMESTAMP");
        assertThat(oracle.renderDefaultValue(ts)).isEqualTo("SYSTIMESTAMP");
        assertThat(sqlServer.renderDefaultValue(ts)).isEqualTo("GETDATE()");
    }

    @Test
    void postgresQuotedDefaultWithACastIsNormalized() {
        ColumnMeta col = column("state", "VARCHAR", Types.VARCHAR, 20);
        col.setDefaultValue("'active'::character varying");
        // The cast must be stripped or the target will reject the literal.
        assertThat(mysql.renderDefaultValue(col)).isEqualTo("'active'");
    }

    @Test
    void autoIncrementColumnDefaultIsNotCopied() {
        // The source's sequence default is meaningless on the target and would conflict with
        // the target's own identity clause.
        ColumnMeta col = column("id", "INT", Types.INTEGER, 10);
        col.setAutoIncrement(true);
        col.setDefaultValue("nextval('t_id_seq')");
        assertThat(postgres.renderDefaultValue(col)).isNull();
    }

    // --- Deletes ----------------------------------------------------------------------

    @Test
    void deleteByPrimaryKeyIsKeyedAndThereforeIdempotent() {
        String sql = mysql.getDeleteByPkSql("s", "orders", List.of("id")).toUpperCase();
        assertThat(sql).startsWith("DELETE FROM").contains("WHERE").contains("= ?");
    }

    @Test
    void deleteWithoutAKeyIsRefused() {
        // A keyless DELETE would wipe the table; refusing is the only safe behaviour.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> mysql.getDeleteByPkSql("s", "orders", List.of()));
    }

    // --- Pagination -------------------------------------------------------------------

    @Test
    void paginationUsesEachProductsSupportedSyntax() {
        String base = "SELECT * FROM t ORDER BY id";
        assertThat(mysql.getPaginationSql(base, 100, 50)).contains("LIMIT 50").contains("OFFSET 100");
        // Oracle's ROWNUM nesting works on releases without OFFSET/FETCH.
        assertThat(oracle.getPaginationSql(base, 100, 50)).contains("ROWNUM");
        assertThat(sqlServer.getPaginationSql(base, 100, 50)).contains("OFFSET 100").contains("FETCH NEXT 50");
    }

    // --- Oracle DATE carries a time component -----------------------------------------

    /**
     * Oracle's DATE stores hours, minutes and seconds. A date-only target type therefore
     * discards the time on every row, without any error to notice.
     */
    @Test
    void oracleDateKeepsItsTimeComponentOnANonOracleTarget() {
        ColumnMeta d = column("created", "DATE", Types.DATE, 7);

        assertThat(mysql.mapType(d, DatabaseType.ORACLE)).isEqualTo("DATETIME");
        assertThat(sqlServer.mapType(d, DatabaseType.ORACLE)).isEqualTo("DATETIME2");
        assertThat(postgres.mapType(d, DatabaseType.ORACLE)).containsIgnoringCase("TIMESTAMP");
        assertThat(db2.mapType(d, DatabaseType.ORACLE)).isEqualTo("TIMESTAMP");
    }

    @Test
    void aDateFromAProductWhereDateIsDateOnlyStaysADate() {
        // Only Oracle overloads DATE this way; widening everyone would be wrong.
        ColumnMeta d = column("birthday", "DATE", Types.DATE, 10);

        assertThat(mysql.mapType(d, DatabaseType.POSTGRESQL)).isEqualTo("DATE");
        assertThat(postgres.mapType(d, DatabaseType.MYSQL)).isEqualTo("DATE");
        // Oracle to Oracle needs no rewrite: DATE already means the same thing there, and
        // changing it would alter date-arithmetic semantics.
        assertThat(oracle.mapType(d, DatabaseType.ORACLE)).isEqualTo("DATE");
    }

    // --- Unconstrained numerics --------------------------------------------------------

    @Test
    void unconstrainedNumericDoesNotProduceAnInvalidPrecision() {
        // Oracle reports NUMBER without precision as size 0; naively emitting DECIMAL(0,0)
        // would be rejected by every target.
        ColumnMeta number = column("qty", "NUMBER", Types.NUMERIC, 0);
        number.setDecimalDigits(0);
        for (SqlDialect dialect : allDialects) {
            String mapped = dialect.mapType(number, DatabaseType.ORACLE);
            assertThat(mapped).doesNotContain("(0,0)").doesNotContain("(0)");
        }
    }

    /**
     * An Oracle NUMBER with neither precision nor scale is a floating decimal, so it can hold
     * 1.5. Mapping it onto a zero-scale target truncates that on every insert and reports no
     * error, which is the worst possible outcome.
     */
    @Test
    void unconstrainedOracleNumberReservesFractionalDigits() {
        for (Integer reportedScale : new Integer[] {null, 0, -127}) {
            ColumnMeta number = column("amount", "NUMBER", Types.NUMERIC, 0);
            number.setDecimalDigits(reportedScale);

            for (SqlDialect dialect : allDialects) {
                String mapped = dialect.mapType(number, DatabaseType.ORACLE);
                assertThat(mapped)
                        .as("dialect %s, driver-reported scale %s",
                                dialect.getClass().getSimpleName(), reportedScale)
                        .doesNotEndWith(",0)");
            }
        }
    }

    @Test
    void aGenuinelyIntegerNumericKeepsScaleZero() {
        // NUMBER(10) really is an integer: the precision is present, so scale 0 is a fact
        // rather than a gap in the metadata.
        ColumnMeta id = column("order_id", "NUMBER", Types.NUMERIC, 10);
        id.setDecimalDigits(0);
        assertThat(mysql.mapType(id, DatabaseType.ORACLE)).isEqualTo("DECIMAL(10,0)");
    }

    @Test
    void declaredPrecisionAndScaleAreCopiedThrough() {
        ColumnMeta price = column("price", "NUMBER", Types.NUMERIC, 12);
        price.setDecimalDigits(2);
        assertThat(mysql.mapType(price, DatabaseType.ORACLE)).isEqualTo("DECIMAL(12,2)");
    }

    /** DB2 rejects a DECIMAL wider than 31 digits, so the shared default must be clamped. */
    @Test
    void db2NeverExceedsItsDecimalCeiling() {
        ColumnMeta wide = column("amount", "NUMBER", Types.NUMERIC, 38);
        wide.setDecimalDigits(4);
        assertThat(db2.mapType(wide, DatabaseType.ORACLE)).isEqualTo("DECIMAL(31,4)");

        ColumnMeta unconstrained = column("amount", "NUMBER", Types.NUMERIC, 0);
        unconstrained.setDecimalDigits(null);
        String mapped = db2.mapType(unconstrained, DatabaseType.ORACLE);
        int precision = Integer.parseInt(mapped.replaceAll("[^0-9,]", "").split(",")[0]);
        assertThat(precision).isLessThanOrEqualTo(31);
    }
}
