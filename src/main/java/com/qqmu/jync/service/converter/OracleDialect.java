package com.qqmu.jync.service.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.DatabaseType;

/**
 * Oracle-family dialect (Oracle, 达梦 DM in Oracle-compatibility mode, 崖山 YashanDB).
 *
 * <p>The Spring-managed instance assumes a modern server; per-connection instances configured
 * with the target's major version come from {@link #withServerMajorVersion(Integer)}.
 */
@Component
public class OracleDialect extends AbstractSqlDialect {

    /** Null = unknown, in which case modern syntax (IDENTITY, 12c+) is assumed. */
    private final Integer majorVersion;

    /**
     * Whether an empty string bound into a CLOB target becomes an empty non-null CLOB.
     * Defaults to true; {@code sync.lob.oracle-empty-string-as-empty-clob=false} restores
     * Oracle's native ''→NULL semantics for installations that relied on them.
     */
    private final boolean emptyStringAsLob;

    /**
     * Whether this instance serves Oracle itself rather than another product that speaks its
     * grammar (达梦, 崖山). Those share the dialect — and the version number the factory pins
     * them to for the identity branch — but not Oracle's 30-byte identifier cap. Defaults to
     * true: the class models Oracle, and only the factory knows the configured product.
     */
    private final boolean oracleProduct;

    public OracleDialect() {
        this(null);
    }

    public OracleDialect(Integer majorVersion) {
        this(majorVersion, true, true);
    }

    private OracleDialect(Integer majorVersion, boolean emptyStringAsLob, boolean oracleProduct) {
        this.majorVersion = majorVersion;
        this.emptyStringAsLob = emptyStringAsLob;
        this.oracleProduct = oracleProduct;
    }

    @Override
    public SqlDialect withServerMajorVersion(Integer majorVersion) {
        return new OracleDialect(majorVersion, emptyStringAsLob, oracleProduct);
    }

    @Override
    public SqlDialect withEmptyStringAsLob(boolean enabled) {
        return new OracleDialect(majorVersion, enabled, oracleProduct);
    }

    @Override
    public SqlDialect forProduct(DatabaseType type) {
        return new OracleDialect(majorVersion, emptyStringAsLob, type == DatabaseType.ORACLE);
    }

    /**
     * Oracle's pre-12.2 identifier cap is 30 <em>bytes</em>, so the byte budget is what keeps a
     * generated sequence or trigger name legal: {@code SEQ_客户订单明细_客户编号} is 15
     * characters — under the character cap — but 35 bytes, and every CREATE for it failed with
     * ORA-00972 after the table itself had been created.
     *
     * <p>Only the real product gets the byte budget. DM and YashanDB accept far longer names,
     * and shortening theirs would rename the sequences of tables that work today; the 12c+
     * limit (128) never binds under the 30-character cap, so modern Oracle is unchanged too.
     */
    @Override
    protected String truncateIdentifier(String name) {
        return oracleProduct
                ? truncateIdentifier(name, maxIdentifierLength(), maxIdentifierBytes())
                : super.truncateIdentifier(name);
    }

    /**
     * 11g caps identifiers at 30 bytes; 12.2 raised the cap to 128. Major 12 spans both
     * releases, and this dialect already treats 12+ as modern, so 12+ gets the later limit.
     * An unknown version applies no limit: refusing a name that may well be legal is worse
     * than the late failure the preflight is meant to improve.
     */
    @Override
    public int maxIdentifierBytes() {
        if (majorVersion == null) {
            return 0;
        }
        return majorVersion >= 12 ? 128 : 30;
    }

    @Override
    public DatabaseType.DialectFamily family() {
        return DatabaseType.DialectFamily.ORACLE;
    }

    @Override
    protected char quoteChar() {
        return '"';
    }

    @Override
    protected String normalizeBareIdentifier(String name) {
        // Oracle folds unquoted identifiers to upper case. A quoted UPPER name is the same
        // object as an unquoted one, so quoting the folded name is safe even for reserved
        // words, and it keeps a lowercase MySQL/PostgreSQL source name from creating a
        // quoted-lowercase twin next to a conventionally pre-created ORDERS table.
        //
        // A name genuinely containing a quote char is an explicitly quoted, possibly
        // mixed-case object — folding it would point the DDL at a different object, so such
        // names pass through with only the quote-doubling escape applied upstream.
        return name.indexOf('"') >= 0 ? name : name.toUpperCase(Locale.ROOT);
    }

    /**
     * 12c introduced {@code AS IDENTITY}. An unknown version does NOT get it: the
     * sequence+trigger emulation is legal on every release (12c+ included), so it is the
     * safe choice when identity support could not actually be verified.
     */
    private boolean supportsIdentity() {
        return majorVersion != null && majorVersion >= 12;
    }

    @Override
    protected String booleanType() {
        // Oracle had no BOOLEAN column type before 23c.
        return "NUMBER(1)";
    }

    @Override
    protected String tinyIntType() {
        return "NUMBER(3)";
    }

    @Override
    protected String smallIntType() {
        return "NUMBER(5)";
    }

    @Override
    protected String intType() {
        return "NUMBER(10)";
    }

    @Override
    protected String bigIntType() {
        return "NUMBER(19)";
    }

    @Override
    protected String floatType() {
        return "BINARY_FLOAT";
    }

    @Override
    protected String doubleType() {
        return "BINARY_DOUBLE";
    }

    @Override
    protected String dateType() {
        return "DATE";
    }

    @Override
    protected String timeType() {
        // Oracle has no TIME type; INTERVAL is the closest portable equivalent, but a
        // timestamp keeps the value readable and comparable.
        return "TIMESTAMP";
    }

    @Override
    protected String timestampType() {
        return "TIMESTAMP";
    }

    @Override
    protected String timestampTzType() {
        return "TIMESTAMP WITH TIME ZONE";
    }

    @Override
    protected String blobType() {
        return "BLOB";
    }

    @Override
    protected String clobType() {
        return "CLOB";
    }

    @Override
    protected String charType(int size) {
        // CHAR likewise defaults to BYTE semantics; declare CHAR semantics so CJK data fits.
        // 500 chars stays within the 2000-byte CHAR ceiling even with 4-byte characters.
        // A wider source cannot be a CHAR here: keep the width as VARCHAR2/CLOB instead of
        // clamping, which would make the column reject its own data (ORA-12899).
        if (size > 500) {
            return varcharType(size);
        }
        return "CHAR(" + Math.max(size, 1) + " CHAR)";
    }

    @Override
    protected String varcharType(int size) {
        // VARCHAR2 defaults to BYTE semantics: VARCHAR2(100) holds 100 bytes, so a CJK value
        // of 34 characters overflows it. CHAR semantics measure in characters instead, but
        // still consume up to 4 bytes/char against the 4000-byte ceiling — only sizes that
        // stay within that bound are safe on a non-EXTENDED database; anything larger (and
        // unknown/zero sizes) becomes a CLOB, which holds the content regardless.
        if (size > 0 && size <= 1000) {
            return "VARCHAR2(" + size + " CHAR)";
        }
        return clobType();
    }

    @Override
    protected String binaryType(int size) {
        if (size <= 0 || size > 2000) {
            return blobType();
        }
        return "RAW(" + size + ")";
    }

    /**
     * A {@code MERGE ... USING (SELECT ? AS c FROM DUAL)} placeholder is bound as a LONG:
     * 32767 bytes, counted in the server charset. Above it the driver raises ORA-01461 even
     * though the column is a CLOB, and no driver convenience (setStringForClob,
     * setCharacterStream, setObject with Types.CLOB) avoids it — only a temporary CLOB does.
     * The binder measures UTF-8 bytes, which over-estimates AL16UTF16/GBK sized text and so
     * sends a borderline value through the safe path.
     */
    @Override
    public int maxInlineBindBytes() {
        return 32767;
    }

    @Override
    public boolean bindsEmptyStringAsLob() {
        return emptyStringAsLob;
    }

    @Override
    public boolean requiresServerSideLob() {
        return true;
    }

    @Override
    public int bindJdbcType(ColumnMeta column, DatabaseType sourceType, int effectiveType) {
        switch (effectiveType) {
            case java.sql.Types.LONGVARCHAR:
            case java.sql.Types.LONGNVARCHAR:
            case java.sql.Types.CLOB:
            case java.sql.Types.NCLOB:
                return java.sql.Types.CLOB;
            case java.sql.Types.LONGVARBINARY:
            case java.sql.Types.BLOB:
                return java.sql.Types.BLOB;
            default:
                break;
        }
        // A source column need not already be a LOB for its TARGET column to be one:
        // varcharType turns anything wider than VARCHAR2(1000 CHAR) — or with no reported
        // width at all — into a CLOB, and binaryType anything over RAW(2000) into a BLOB.
        // The MERGE placeholder type comes from this method, so such a column would keep
        // binding its ordinary String/byte[]: above 32767 bytes it hits ORA-01461 anyway,
        // and an empty string misses the ''→empty-CLOB rescue. Follow the type the column
        // was actually created with (mapByJdbcType is the same input CREATE TABLE uses;
        // it never logs, so the per-row CDC path stays quiet).
        if (column != null && isWidthPromotable(effectiveType)) {
            String mapped = mapByJdbcType(column, sourceType);
            if (clobType().equals(mapped)) {
                return java.sql.Types.CLOB;
            }
            if (blobType().equals(mapped)) {
                return java.sql.Types.BLOB;
            }
        }
        return effectiveType;
    }

    /** The declared-width categories whose target type depends on the width. */
    private static boolean isWidthPromotable(int jdbcType) {
        switch (jdbcType) {
            case java.sql.Types.CHAR:
            case java.sql.Types.NCHAR:
            case java.sql.Types.VARCHAR:
            case java.sql.Types.NVARCHAR:
            case java.sql.Types.BINARY:
            case java.sql.Types.VARBINARY:
                return true;
            default:
                return false;
        }
    }

    @Override
    protected int maxIdentifierLength() {
        // 30 before 12.2; 128 after. 30 is the safe choice across versions.
        return 30;
    }

    @Override
    protected boolean indexNamesAreGlobal() {
        return true;
    }

    /**
     * Oracle's column grammar is {@code column_name data_type [DEFAULT expr]
     * [inline_constraint ...]}, so a {@code DEFAULT} written after {@code NOT NULL} — the MySQL
     * order — is parsed as a stray token. Pre-23 servers report it as the bare
     * ORA-00907 "missing right parenthesis"; 23ai names it: ORA-03076 "unexpected item DEFAULT
     * in column definition or internal constraint condition".
     */
    @Override
    protected boolean defaultBeforeNotNull() {
        return true;
    }

    /**
     * Oracle converts a VARCHAR2 bind into DATE/TIMESTAMP through the session's
     * NLS_DATE_FORMAT/NLS_TIMESTAMP_FORMAT — the default {@code DD-MON-RR} — so the MySQL
     * wall-clock literal the reader produces ("2026-10-13 12:34:56.789012") fails with
     * ORA-01843 "not a valid month". There is no portable in-SQL cast for the binder to
     * lean on the way the PostgreSQL family leans on {@code ?::timestamp}: wrapping the
     * placeholder in {@code TO_TIMESTAMP(?)} would send already-typed
     * {@code Timestamp} binds back through a text round-trip. So the literal is parsed
     * into a {@code java.sql.Timestamp} before binding instead.
     */
    @Override
    public boolean requiresTypedTemporalBind() {
        return true;
    }

    @Override
    protected String currentTimestampFunction() {
        return "SYSTIMESTAMP";
    }

    /**
     * Oracle 12c+ identity column. Pre-12 targets get the plain type here and the matching
     * sequence + BEFORE INSERT trigger from {@link #getPostCreateTableDdl}.
     */
    @Override
    protected String autoIncrementColumnType(ColumnMeta col, DatabaseType sourceType) {
        String type = mapType(col, sourceType);
        return supportsIdentity()
                ? type + " GENERATED BY DEFAULT ON NULL AS IDENTITY"
                : type;
    }

    @Override
    public List<String> getPostCreateTableDdl(TableMeta table, String schema, String tableName,
                                              DatabaseType sourceType) {
        if (supportsIdentity()) {
            return List.of();
        }
        List<String> stmts = new ArrayList<>();
        for (ColumnMeta col : table.getColumns()) {
            if (col.isAutoIncrement()) {
                stmts.addAll(legacyIdentityDdl(schema, tableName, col));
            }
        }
        return stmts;
    }

    @Override
    public List<String> getPostColumnChangeDdl(String schema, String table, ColumnMeta column,
                                               DatabaseType sourceType) {
        if (supportsIdentity() || !column.isAutoIncrement()) {
            return List.of();
        }
        return legacyIdentityDdl(schema, table, column);
    }

    /**
     * Sequence + BEFORE-INSERT trigger emulating {@code BY DEFAULT ON NULL} identity on
     * pre-12c Oracle: an explicitly supplied value is kept, an omitted/null one is drawn
     * from the sequence. Names are deterministic (so re-runs converge) and capped at the
     * 30-byte identifier limit by {@link #truncateIdentifier}.
     */
    private List<String> legacyIdentityDdl(String schema, String table, ColumnMeta col) {
        String sequence = truncateIdentifier("SEQ_" + table + "_" + col.getName());
        String trigger = truncateIdentifier("TRG_" + table + "_" + col.getName());
        String column = quoteIdentifier(col.getName());
        List<String> stmts = new ArrayList<>(2);
        stmts.add("CREATE SEQUENCE " + quoteIdentifier(sequence)
                + " START WITH 1 INCREMENT BY 1 NOCACHE");
        stmts.add("CREATE OR REPLACE TRIGGER " + quoteIdentifier(trigger)
                + " BEFORE INSERT ON " + qualify(schema, table)
                + " FOR EACH ROW BEGIN"
                + " IF :NEW." + column + " IS NULL THEN"
                + " SELECT " + quoteIdentifier(sequence) + ".NEXTVAL"
                + " INTO :NEW." + column + " FROM DUAL;"
                + " END IF; END;");
        return stmts;
    }

    @Override
    public List<String> getIdentityReseedDdl(String schema, String table, ColumnMeta column,
                                             long nextValue) {
        if (supportsIdentity()) {
            // Re-declares the identity options with a fresh start value; legal on a column
            // that is already an identity column (the tables the sync creates always are).
            return List.of("ALTER TABLE " + qualify(schema, table) + " MODIFY ("
                    + quoteIdentifier(column.getName())
                    + " GENERATED BY DEFAULT ON NULL AS IDENTITY (START WITH " + nextValue
                    + "))");
        }
        // Pre-12: the sequence is ours, so drop-and-recreate at the new start. The DROP is
        // best-effort (it may not exist yet on a partial replay); callers run each statement
        // independently. The dependent trigger is recompiled by the CREATE OR REPLACE.
        String sequence = truncateIdentifier("SEQ_" + table + "_" + column.getName());
        List<String> stmts = new ArrayList<>(3);
        stmts.add("DROP SEQUENCE " + quoteIdentifier(sequence));
        stmts.add("CREATE SEQUENCE " + quoteIdentifier(sequence)
                + " START WITH " + nextValue + " INCREMENT BY 1 NOCACHE");
        stmts.add(legacyIdentityDdl(schema, table, column).get(1));
        return stmts;
    }

    @Override
    public String getModifyColumnSql(String schema, String table, ColumnMeta column,
                                     DatabaseType sourceType) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ")
                .append(qualify(schema, table))
                .append(" MODIFY (")
                .append(quoteIdentifier(column.getName())).append(' ')
                .append(mapType(column, sourceType));
        // MODIFY previously carried no identity clause, so an ALTER detected against an
        // identity column silently demoted it to a plain column. On 12c+ keep the property;
        // pre-12 gets the sequence/trigger pair from getPostColumnChangeDdl.
        if (column.isAutoIncrement() && supportsIdentity()) {
            sb.append(" GENERATED BY DEFAULT ON NULL AS IDENTITY");
        }
        // Oracle rejects MODIFY ... NOT NULL when the column is already NOT NULL
        // (ORA-01442) — and a type widening of a NOT NULL column is the common case here,
        // since the change event carries no "previous nullability" to compare against.
        // So state nullability only when relaxing; omitting the clause preserves whatever
        // the column currently has. (A genuine nullable→NOT NULL tightening is therefore
        // not applied by this statement — the known limitation shared with the PG/DB2
        // dialects, tracked separately from the ORA-01442 crash this avoids.)
        if (column.isNullable()) {
            sb.append(" NULL");
        }
        return sb.append(')').toString();
    }

    @Override
    public String getAddColumnSql(String schema, String table, ColumnMeta column,
                                  DatabaseType sourceType) {
        // Oracle's ADD takes no COLUMN keyword.
        return "ALTER TABLE " + qualify(schema, table) + " ADD ("
                + columnDefinition(column, sourceType) + ")";
    }

    @Override
    public List<String> getCreateViewSql(ViewMeta view, String schema, DatabaseType sourceType) {
        return List.of("CREATE OR REPLACE VIEW " + qualify(schema, view.getName())
                + " AS " + stripTrailingSemicolon(view.getDefinition()));
    }

    @Override
    public List<String> getCreateProcedureSql(ProcedureMeta procedure, String schema,
                                              DatabaseType sourceType) {
        String body = procedure.getDefinition();
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String trimmed = stripTrailingSlash(body.trim());
        List<String> stmts = new ArrayList<>();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CREATE OR REPLACE")) {
            stmts.add(trimmed);
        } else if (upper.startsWith("CREATE")) {
            // Make it re-runnable: ALL_SOURCE stores "PROCEDURE x" without CREATE.
            stmts.add(trimmed.replaceFirst("(?i)^CREATE\\s+", "CREATE OR REPLACE "));
        } else if (upper.startsWith("PROCEDURE") || upper.startsWith("FUNCTION")) {
            stmts.add("CREATE OR REPLACE " + trimmed);
        } else {
            stmts.add("CREATE OR REPLACE " + (procedure.isFunction() ? "FUNCTION " : "PROCEDURE ")
                    + qualify(schema, procedure.getName()) + " AS\n" + trimmed);
        }
        return stmts;
    }

    private String stripTrailingSemicolon(String sql) {
        if (sql == null) {
            return null;
        }
        String s = sql.trim();
        // Oracle rejects a trailing semicolon on a plain DDL statement submitted via JDBC.
        return s.endsWith(";") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Removes the SQL*Plus terminator, which JDBC does not accept.
     *
     * <p>A terminator is a {@code /} alone on the last line, and only that. Stripping a bare
     * trailing slash instead also ate the closing delimiter of a body that ends in a block
     * comment, submitting an unterminated comment that Oracle rejects — the routine then never
     * gets created.
     */
    private String stripTrailingSlash(String sql) {
        String s = sql.trim();
        while (!s.isEmpty()) {
            int lastBreak = s.lastIndexOf('\n');
            String lastLine = (lastBreak < 0 ? s : s.substring(lastBreak + 1)).trim();
            if (!"/".equals(lastLine)) {
                break;
            }
            s = lastBreak < 0 ? "" : s.substring(0, lastBreak).trim();
        }
        return s;
    }

    /**
     * {@code MERGE} is Oracle's idempotent upsert. Values are bound twice — once for the
     * USING row source and once for the INSERT list — which is what
     * {@link #upsertBindOrder} encodes.
     */
    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns) {
        return getUpsertSql(schema, table, columns, pkColumns, null, false);
    }

    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns, java.util.Map<String, Integer> types) {
        return getUpsertSql(schema, table, columns, pkColumns, types, false);
    }

    @Override
    public String getUpsertSql(String schema, String table, List<String> columns,
                               List<String> pkColumns, java.util.Map<String, Integer> types,
                               boolean guardUnchangedRows) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            return "INSERT INTO " + qualify(schema, table) + " (" + quotedList(columns)
                    + ") VALUES (" + placeholders(columns.size()) + ")";
        }
        String using = columns.stream()
                .map(c -> "? AS " + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        String onClause = pkColumns.stream()
                .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                .collect(Collectors.joining(" AND "));
        List<String> updatable = nonKeyColumns(columns, pkColumns);

        StringBuilder sb = new StringBuilder();
        sb.append("MERGE INTO ").append(qualify(schema, table)).append(" t USING (SELECT ")
                .append(using).append(" FROM DUAL) s ON (").append(onClause).append(')');
        if (!updatable.isEmpty()) {
            String updates = updatable.stream()
                    .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                    .collect(Collectors.joining(", "));
            sb.append(" WHEN MATCHED THEN UPDATE SET ").append(updates);
            String guard = guardUnchangedRows
                    ? UpsertChangeGuard.condition(updatable,
                            c -> "t." + quoteIdentifier(c), c -> "s." + quoteIdentifier(c))
                    : null;
            if (guard != null) {
                // 值没变就不写：MERGE 对无变化的重放也报 1 影响行（实测），于是一轮安全余量
                // 重放就是一条假的 updated 日志。加了条件后无变化报 0，与 MySQL 目标一致。
                sb.append(" WHERE ").append(guard);
            }
        }
        String insertCols = quotedList(columns);
        String insertVals = columns.stream()
                .map(c -> "s." + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        sb.append(" WHEN NOT MATCHED THEN INSERT (").append(insertCols)
                .append(") VALUES (").append(insertVals).append(')');
        return sb.toString();
    }

    @Override
    public List<String> upsertBindOrder(List<String> columns, List<String> pkColumns) {
        // The MERGE names each value once, in the USING clause.
        return new ArrayList<>(columns);
    }

    @Override
    public boolean supportsInsertFirstInitialLoad() {
        return true;
    }

    @Override
    public String emptyTargetProbeSql(String schema, String table) {
        // Oracle has no LIMIT; ROWNUM stops the scan after the first row.
        return "SELECT 1 FROM " + qualify(schema, table) + " WHERE ROWNUM = 1";
    }

    @Override
    public String getPaginationSql(String baseSql, long offset, int limit) {
        // OFFSET/FETCH requires 12c; ROWNUM nesting works on every supported release.
        // offset 0 (the keyset page-cap case) must not use the rnum_ helper: the wrapper
        // would project it into the outer SELECT *, and the data copy builds its INSERT
        // column list from the result metadata — a leaked RNUM_ column lands in the target
        // statement and every chunk fails with "column not found".
        if (offset <= 0) {
            return "SELECT * FROM (" + baseSql + ") WHERE ROWNUM <= " + limit;
        }
        return "SELECT * FROM (SELECT a.*, ROWNUM rnum_ FROM (" + baseSql + ") a "
                + "WHERE ROWNUM <= " + (offset + limit) + ") WHERE rnum_ > " + offset;
    }

    @Override
    public String getProjectedPaginationSql(String baseSql, List<String> columnRefs,
                                            long offset, int limit) {
        // The offset wrapper needs the rnum_ helper column; an outer SELECT * would project it
        // into the result metadata as well (see getPaginationSql). Naming the columns here keeps
        // the copied row shape exactly the table's own.
        if (offset <= 0) {
            return getPaginationSql(baseSql, offset, limit);
        }
        return "SELECT " + String.join(", ", columnRefs)
                + " FROM (SELECT a.*, ROWNUM rnum_ FROM (" + baseSql + ") a "
                + "WHERE ROWNUM <= " + (offset + limit) + ") WHERE rnum_ > " + offset;
    }

    @Override
    public String getTruncateSql(String schema, String table) {
        return "TRUNCATE TABLE " + qualify(schema, table);
    }
}
