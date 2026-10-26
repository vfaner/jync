package com.qqmu.jync.service.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

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
        assertThat(oracle.mapType(narrow, DatabaseType.MYSQL)).isEqualTo("VARCHAR2(50 CHAR)");
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
        // (6) keeps fractional seconds; plain DATETIME would truncate every synced row.
        assertThat(mysql.mapType(ts, DatabaseType.POSTGRESQL)).isEqualTo("DATETIME(6)");
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

    /**
     * Source object names are just bytes from the dictionary: a MySQL table may legitimately be
     * named {@code `a`; DROP TABLE victim; -- `} (backticks are ordinary characters inside a
     * quoted identifier). The "already wrapped" shortcut must not take that for quoting it can
     * trust — returning it verbatim closes the identifier early and appends a second statement
     * to every DDL/DML Jync sends to the target. Only a name whose interior quotes are all
     * doubled may pass through untouched; anything else is re-quoted as a literal name.
     */
    @Test
    void aWrappedNameWithALoneInteriorQuoteIsRequotedInsteadOfPassedThrough() {
        assertThat(mysql.quoteIdentifier("`a`; DROP TABLE victim; -- `"))
                .isEqualTo("```a``; DROP TABLE victim; -- ```");
        assertThat(sqlServer.quoteIdentifier("[a]; DROP TABLE victim; -- ]"))
                .isEqualTo("[[a]]; DROP TABLE victim; -- ]]]");
        // A genuinely wrapped name whose interior quote is doubled keeps its exact form.
        assertThat(mysql.quoteIdentifier("`a``b`")).isEqualTo("`a``b`");
        assertThat(sqlServer.quoteIdentifier("[a]]b]")).isEqualTo("[a]]b]");
    }

    @Test
    void anEmbeddedQuoteCharacterIsEscapedNotStripped() {
        // An identifier really containing the quote char must be escaped by doubling it.
        // Stripping the character would silently produce SQL for a different object.
        assertThat(postgres.quoteIdentifier("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(mysql.quoteIdentifier("a`b")).isEqualTo("`a``b`");
        assertThat(sqlServer.quoteIdentifier("a]b")).isEqualTo("[a]]b]");
        assertThat(oracle.quoteIdentifier("a\"b")).isEqualTo("\"a\"\"b\"");
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
        String sqlServerDdl = sqlServer.getCreateTableSql(table, null, "t", DatabaseType.MYSQL);
        assertThat(sqlServerDdl).contains("IDENTITY");
        // T-SQL requires IDENTITY before NOT NULL: the old "INT NOT NULL IDENTITY(1,1)"
        // made CREATE TABLE fail with a syntax error.
        assertThat(sqlServerDdl).contains("[id] BIGINT IDENTITY(1,1) NOT NULL");

        // The dialect supplies the session switch that lets the source's own id through
        // (without it every explicit insert fails with SQL Server error 544).
        assertThat(sqlServer.getSetIdentityInsertSql("dbo", "t", true))
                .isEqualTo("SET IDENTITY_INSERT [dbo].[t] ON");
        assertThat(sqlServer.getSetIdentityInsertSql("dbo", "t", false))
                .isEqualTo("SET IDENTITY_INSERT [dbo].[t] OFF");
        // Every other dialect needs nothing.
        assertThat(mysql.getSetIdentityInsertSql(null, "t", true)).isNull();
        assertThat(postgres.getSetIdentityInsertSql(null, "t", true)).isNull();
    }

    @Test
    void wideOracleIdentityBecomesBigserialNotA32BitSerial() {
        // Oracle identity columns are NUMBER, which maps to DECIMAL(19,0) here. SERIAL is a
        // 32-bit INTEGER, so every key above 2^31 would fail to insert.
        TableMeta table = new TableMeta();
        table.setName("t");
        ColumnMeta id = column("id", "NUMBER", Types.NUMERIC, 19);
        id.setDecimalDigits(0);
        id.setNullable(false);
        id.setAutoIncrement(true);
        id.setPrimaryKey(true);
        table.setColumns(List.of(id));
        table.setPrimaryKeys(List.of("id"));

        String ddl = postgres.getCreateTableSql(table, null, "t", DatabaseType.ORACLE);
        assertThat(ddl).contains("BIGSERIAL");

        // The ordinary INT identity keeps the narrow form: widening every sequence would
        // change existing target schemas for no gain.
        ColumnMeta intId = column("id", "INT", Types.INTEGER, 10);
        intId.setNullable(false);
        intId.setAutoIncrement(true);
        intId.setPrimaryKey(true);
        TableMeta intTable = new TableMeta();
        intTable.setName("t");
        intTable.setColumns(List.of(intId));
        intTable.setPrimaryKeys(List.of("id"));
        assertThat(postgres.getCreateTableSql(intTable, null, "t", DatabaseType.MYSQL))
                .contains("\"id\" SERIAL");
    }

    @Test
    void db2IdentityColumnIsNotFollowedByNotNull() {
        // DB2's grammar is "<type> [NOT NULL] GENERATED ... AS IDENTITY"; appending NOT NULL
        // AFTER the identity clause is a syntax error (SQL0104). Identity columns are
        // implicitly NOT NULL anyway, so the clause must simply be omitted for DB2.
        TableMeta table = new TableMeta();
        table.setName("t");
        ColumnMeta id = column("id", "BIGINT", Types.BIGINT, 19);
        id.setNullable(false);
        id.setAutoIncrement(true);
        id.setPrimaryKey(true);
        table.setColumns(List.of(id));
        table.setPrimaryKeys(List.of("id"));

        String ddl = db2.getCreateTableSql(table, null, "t", DatabaseType.MYSQL);
        assertThat(ddl).contains("GENERATED BY DEFAULT AS IDENTITY");
        assertThat(ddl).doesNotContain("IDENTITY NOT NULL");
    }

    @Test
    void mysqlAutoIncrementColumnWithoutAPrimaryKeyGetsAUniqueKey() {
        // MySQL rejects CREATE/ALTER with ER_WRONG_AUTO_KEY (1075): an AUTO_INCREMENT column
        // must be defined as a key. A PostgreSQL SERIAL column that is not declared PRIMARY
        // KEY has no index, so its MySQL target needs an explicit UNIQUE KEY.
        TableMeta table = new TableMeta();
        table.setName("events");
        ColumnMeta id = column("id", "BIGINT", Types.BIGINT, 19);
        id.setNullable(false);
        id.setAutoIncrement(true);
        table.setColumns(List.of(id));
        table.setPrimaryKeys(List.of());

        String ddl = mysql.getCreateTableSql(table, null, "events", DatabaseType.POSTGRESQL);
        assertThat(ddl).contains("AUTO_INCREMENT");
        assertThat(ddl).containsIgnoringCase("UNIQUE KEY");
        assertThat(ddl).contains("`id`");
    }

    @Test
    void mysqlAlterAddAutoIncrementColumnWithoutAPrimaryKeyCarriesTheUniqueKeyInOneStatement() {
        // MySQL enforces ER_WRONG_AUTO_KEY (1075) on ALTER ADD COLUMN too: a standalone
        // "ADD COLUMN id ... AUTO_INCREMENT" fails unless the key is in the SAME statement.
        // A separate companion DDL would run too late, after the ADD already failed.
        ColumnMeta id = column("id", "BIGINT", Types.BIGINT, 19);
        id.setNullable(false);
        id.setAutoIncrement(true);

        String ddl = mysql.getAddColumnSql(null, "events", id, DatabaseType.POSTGRESQL);
        assertThat(ddl).contains("ADD COLUMN").contains("AUTO_INCREMENT");
        assertThat(ddl).containsIgnoringCase("ADD UNIQUE KEY").contains("`id`");
        // No separate companion statement — the key rides along in the single ALTER, and a
        // MODIFY of an existing AI column already has its key from when it was added.
        assertThat(mysql.getPostColumnChangeDdl(null, "events", id,
                DatabaseType.POSTGRESQL)).isEmpty();

        // An AI column that is already a key gets a plain ADD with no unique key.
        ColumnMeta pkId = column("id", "BIGINT", Types.BIGINT, 19);
        pkId.setNullable(false);
        pkId.setAutoIncrement(true);
        pkId.setPrimaryKey(true);
        assertThat(mysql.getAddColumnSql(null, "events", pkId, DatabaseType.POSTGRESQL))
                .doesNotContainIgnoringCase("UNIQUE KEY");
    }

    @Test
    void mysqlAutoIncrementPrimaryKeyIsNotGivenARedundantUniqueKey() {
        // The normal case (AUTO_INCREMENT PK) is already a key; no extra constraint.
        TableMeta table = new TableMeta();
        table.setName("events");
        ColumnMeta id = column("id", "BIGINT", Types.BIGINT, 19);
        id.setNullable(false);
        id.setAutoIncrement(true);
        id.setPrimaryKey(true);
        table.setColumns(List.of(id));
        table.setPrimaryKeys(List.of("id"));

        assertThat(mysql.getCreateTableSql(table, null, "events", DatabaseType.MYSQL))
                .doesNotContainIgnoringCase("UNIQUE KEY");
    }

    @Test
    void pseudoNumericDefaultsAreNotEmittedAsNumericLiterals() {
        // Double.parseDouble accepts NaN, Infinity and hex doubles (0x1p4); no target
        // accepts those as DEFAULT literals, so they must be dropped like other
        // non-portable defaults rather than failing CREATE TABLE.
        for (String raw : new String[] {"NaN", "Infinity", "-Infinity", "0x1p4"}) {
            ColumnMeta col = column("v", "DOUBLE", Types.DOUBLE, 22);
            col.setDefaultValue(raw);
            assertThat(mysql.renderDefaultValue(col))
                    .as("default %s must be skipped", raw).isNull();
        }
        ColumnMeta ok = column("v", "DOUBLE", Types.DOUBLE, 22);
        ok.setDefaultValue("1.5");
        assertThat(mysql.renderDefaultValue(ok)).isEqualTo("1.5");
    }

    @Test
    void timeWithTimeZoneKeepsItsOffsetTypeOnPostgresFamilyTargets() {
        ColumnMeta tz = column("tz", "TIMETZ", Types.TIME_WITH_TIMEZONE, 6);
        assertThat(postgres.mapType(tz, DatabaseType.POSTGRESQL))
                .isEqualTo("TIME WITH TIME ZONE");
        assertThat(new OpenGaussDialect().mapType(tz, DatabaseType.POSTGRESQL))
                .isEqualTo("TIME WITH TIME ZONE");
        // Targets without a TIME ZONE type keep the plain time mapping (offset dropped,
        // but the DDL stays legal).
        assertThat(mysql.mapType(tz, DatabaseType.POSTGRESQL)).isEqualTo("TIME");
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
    void currentTimestampDefaultsSurviveATurkishDefaultLocale() {
        // 默认 locale 的大写会把 'i' 映射为 'İ'：CURRENT_TIMESTAMP 变成 CURRENT_TİMESTAMP，
        // 与字面量比对失败，列的当前时间默认值会被静默丢弃（只留一条 debug 日志）。
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            ColumnMeta ts = column("created", "TIMESTAMP", Types.TIMESTAMP, 26);
            ts.setDefaultValue("current_timestamp");
            assertThat(oracle.renderDefaultValue(ts)).isEqualTo("SYSTIMESTAMP");
            assertThat(sqlServer.renderDefaultValue(ts)).isEqualTo("GETDATE()");

            ColumnMeta sys = column("created", "TIMESTAMP", Types.TIMESTAMP, 26);
            sys.setDefaultValue("systimestamp");
            assertThat(postgres.renderDefaultValue(sys)).isEqualTo("CURRENT_TIMESTAMP");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void mysqlCurrentTimestampDefaultCarriesTheColumnFractionalPrecision() {
        // MySQL rejects DATETIME(6) DEFAULT CURRENT_TIMESTAMP as a precision mismatch
        // (error 1067, "Invalid default value"); the default must repeat the column's (6).
        ColumnMeta ts = column("update_time", "TIMESTAMP", Types.TIMESTAMP, 26);
        ts.setDefaultValue("CURRENT_TIMESTAMP");
        assertThat(mysql.renderDefaultValue(ts)).isEqualTo("CURRENT_TIMESTAMP(6)");

        TableMeta table = new TableMeta();
        table.setName("orders");
        table.setColumns(List.of(ts));
        assertThat(mysql.getCreateTableSql(table, "cee", "orders", DatabaseType.MYSQL))
                .contains("DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)");
    }

    @Test
    void postgresQuotedDefaultWithACastIsNormalized() {
        ColumnMeta col = column("state", "VARCHAR", Types.VARCHAR, 20);
        col.setDefaultValue("'active'::character varying");
        // The cast must be stripped or the target will reject the literal.
        assertThat(mysql.renderDefaultValue(col)).isEqualTo("'active'");
    }

    @Test
    void aCastInsideTheLiteralIsNotMistakenForATrailingCast() {
        // PostgreSQL appends the column cast after the literal. The literal itself may
        // contain "::", and stripping at the first occurrence truncates the default into an
        // unterminated string, which the target rejects with a syntax error.
        ColumnMeta col = column("pattern", "VARCHAR", Types.VARCHAR, 20);
        col.setDefaultValue("'a::b'::character varying");
        assertThat(mysql.renderDefaultValue(col)).isEqualTo("'a::b'");
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
        // But the offset-0 page cap must not project the rnum_ helper column: the data
        // copy builds its INSERT column list from the result metadata, so a leaked helper
        // would be written to the target and fail every chunk.
        assertThat(oracle.getPaginationSql(base, 0, 50))
                .contains("ROWNUM <= 50").doesNotContain("rnum_");
        assertThat(sqlServer.getPaginationSql(base, 100, 50)).contains("OFFSET 100").contains("FETCH NEXT 50");
        // DB2 has no LIMIT clause: its row-limit grammar is FETCH FIRST / OFFSET ... FETCH NEXT.
        // Keyset chunking calls this on every page, so LIMIT here breaks every DB2 full load.
        assertThat(db2.getPaginationSql(base, 0, 50))
                .contains("FETCH FIRST 50 ROWS ONLY").doesNotContain("LIMIT");
        assertThat(db2.getPaginationSql(base, 100, 50))
                .contains("OFFSET 100 ROWS").contains("FETCH NEXT 50 ROWS ONLY");
    }

    @Test
    void sqlServerCapsTheFirstPageWithTopBecause2008HasNoOffsetFetch() {
        // OFFSET/FETCH arrived in SQL Server 2012. The keyset chunker only ever asks for
        // offset 0, so on 2008/R2 the old form made EVERY chunked full load fail with a
        // syntax error; TOP is the 2005+ equivalent of "offset 0, take n".
        String base = "SELECT * FROM t WHERE id > ? ORDER BY id";
        assertThat(sqlServer.getPaginationSql(base, 0, 50))
                .isEqualTo("SELECT TOP (50) * FROM t WHERE id > ? ORDER BY id")
                .doesNotContain("OFFSET")
                .doesNotContain("FETCH");
    }

    @Test
    void sqlServerTopGoesAfterDistinct() {
        // TOP sits between SELECT and the select list; a DISTINCT that got pushed behind it
        // would be a syntax error.
        assertThat(sqlServer.getPaginationSql("SELECT DISTINCT id FROM t ORDER BY id", 0, 10))
                .isEqualTo("SELECT DISTINCT TOP (10) id FROM t ORDER BY id");
    }

    @Test
    void sqlServerKeepsOffsetFetchForPagesPastTheFirst() {
        assertThat(sqlServer.getPaginationSql("SELECT * FROM t ORDER BY id", 100, 50))
                .contains("OFFSET 100").contains("FETCH NEXT 50");
    }

    // --- Oracle DATE carries a time component -----------------------------------------

    /**
     * The whole Oracle family (Oracle, DM, YashanDB) stores hours, minutes and seconds in
     * DATE. A date-only target type therefore discards the time on every row, without any
     * error to notice.
     */
    @Test
    void oracleDateKeepsItsTimeComponentOnANonOracleTarget() {
        ColumnMeta d = column("created", "DATE", Types.DATE, 7);

        assertThat(mysql.mapType(d, DatabaseType.ORACLE)).isEqualTo("DATETIME(6)");
        assertThat(sqlServer.mapType(d, DatabaseType.ORACLE)).isEqualTo("DATETIME2");
        assertThat(postgres.mapType(d, DatabaseType.ORACLE)).containsIgnoringCase("TIMESTAMP");
        assertThat(db2.mapType(d, DatabaseType.ORACLE)).isEqualTo("TIMESTAMP");

        // Family members share the semantics, so they must share the widening.
        assertThat(mysql.mapType(d, DatabaseType.DM)).isEqualTo("DATETIME(6)");
        assertThat(mysql.mapType(d, DatabaseType.YASHANDB)).isEqualTo("DATETIME(6)");
        assertThat(postgres.mapType(d, DatabaseType.YASHANDB)).containsIgnoringCase("TIMESTAMP");
    }

    @Test
    void aDateFromAProductWhereDateIsDateOnlyStaysADate() {
        // Only Oracle overloads DATE this way; widening everyone would be wrong.
        ColumnMeta d = column("birthday", "DATE", Types.DATE, 10);

        assertThat(mysql.mapType(d, DatabaseType.POSTGRESQL)).isEqualTo("DATE");
        assertThat(postgres.mapType(d, DatabaseType.MYSQL)).isEqualTo("DATE");
        // Oracle to Oracle needs no rewrite: DATE already means the same thing there, and
        // changing it would alter date-arithmetic semantics. The same holds within the
        // whole Oracle family.
        assertThat(oracle.mapType(d, DatabaseType.ORACLE)).isEqualTo("DATE");
        assertThat(oracle.mapType(d, DatabaseType.YASHANDB)).isEqualTo("DATE");
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
     * An Oracle-family NUMBER with neither precision nor scale is a floating decimal, so it
     * can hold 1.5. Mapping it onto a zero-scale target truncates that on every insert and
     * reports no error, which is the worst possible outcome. DM and YashanDB inherit the
     * NUMBER semantics along with the dictionary layout.
     */
    @Test
    void unconstrainedOracleNumberReservesFractionalDigits() {
        List<DatabaseType> oracleFamily =
                List.of(DatabaseType.ORACLE, DatabaseType.DM, DatabaseType.YASHANDB);
        for (Integer reportedScale : new Integer[] {null, 0, -127}) {
            ColumnMeta number = column("amount", "NUMBER", Types.NUMERIC, 0);
            number.setDecimalDigits(reportedScale);

            for (SqlDialect dialect : allDialects) {
                for (DatabaseType source : oracleFamily) {
                    String mapped = dialect.mapType(number, source);
                    assertThat(mapped)
                            .as("dialect %s, source %s, driver-reported scale %s",
                                    dialect.getClass().getSimpleName(), source, reportedScale)
                            .doesNotEndWith(",0)");
                }
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

    // --- Postgres dollar quoting ---------------------------------------------------------

    /**
     * A body that already contains {@code $jync$} (nested dollar-quoting from the source)
     * would terminate the wrapper's quoting early, producing a statement that is either a
     * syntax error or — worse — two statements. The tag must be bumped until it is unique.
     */
    @Test
    void postgresDollarTagCollidingWithTheBodyIsBumped() {
        assertThat(PostgresDialect.uniqueDollarTag("BEGIN RETURN 1; END")).isEqualTo("jync");
        assertThat(PostgresDialect.uniqueDollarTag("x $jync$ y")).isEqualTo("jync2");
        assertThat(PostgresDialect.uniqueDollarTag("$jync$ and $jync2$")).isEqualTo("jync3");

        com.qqmu.jync.dto.meta.ProcedureMeta proc = new com.qqmu.jync.dto.meta.ProcedureMeta();
        proc.setName("p");
        proc.setDefinition("DECLARE t text := $jync$inner$jync$; BEGIN END");
        String ddl = String.join("", postgres.getCreateProcedureSql(proc, "s", DatabaseType.MYSQL));
        // The wrapper's own quoting uses the bumped tag at both ends.
        assertThat(ddl).contains("AS $jync2$\n").endsWith("\n$jync2$");
    }

    // --- Oracle MODIFY nullability ------------------------------------------------------

    /**
     * The commonest Oracle ALTER is a type widening on a NOT NULL column, where the target
     * column is already NOT NULL. MODIFY ... NOT NULL then fails with ORA-01442 ("column to
     * be modified to NOT NULL is already NOT NULL"), and because the engine deliberately
     * keeps the old snapshot after a failed change, every subsequent poll re-attempts and
     * re-fails forever. Nullability may only be stated when relaxing.
     */
    @Test
    void oracleModifyNeverStatesNotNull() {
        ColumnMeta notNull = column("amount", "NUMBER", Types.NUMERIC, 10);
        notNull.setNullable(false);
        assertThat(oracle.getModifyColumnSql("s", "orders", notNull, DatabaseType.ORACLE))
                .doesNotContain("NOT NULL");

        ColumnMeta relaxed = column("amount", "NUMBER", Types.NUMERIC, 10);
        relaxed.setNullable(true);
        assertThat(oracle.getModifyColumnSql("s", "orders", relaxed, DatabaseType.ORACLE))
                .contains(" NULL");
    }

    // --- Streaming reads ----------------------------------------------------------------

    /**
     * Connector/J and the MySQL-protocol forks (MariaDB, OceanBase, TiDB, GBase) buffer the
     * whole result set client-side unless the fetch size is the Integer.MIN_VALUE sentinel;
     * a full load of a big table would otherwise risk OOM before a single row is written.
     */
    @Test
    void mysqlProtocolSourcesStreamWithTheDriverSentinel() {
        assertThat(mysql.streamingFetchSize(1000)).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void otherDialectsKeepTheConfiguredFetchSize() {
        // These drivers honor a plain fetch size, so the configured paging must survive.
        assertThat(postgres.streamingFetchSize(1000)).isEqualTo(1000);
        assertThat(oracle.streamingFetchSize(500)).isEqualTo(500);
        assertThat(sqlServer.streamingFetchSize(1000)).isEqualTo(1000);
        assertThat(db2.streamingFetchSize(1000)).isEqualTo(1000);
        assertThat(new GenericSqlDialect().streamingFetchSize(1000)).isEqualTo(1000);
    }
}
