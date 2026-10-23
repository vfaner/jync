package com.qqmu.jync.service.converter;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared dialect behaviour: identifier quoting, CREATE TABLE assembly, index DDL and
 * JDBC-type-driven type mapping.
 *
 * <p>Type mapping keys off {@link ColumnMeta#getJdbcType()} rather than the product type
 * name wherever possible. The driver has already classified the column into a portable
 * category, which is more reliable than matching a long tail of product-specific spellings.
 * Subclasses only need to name their own types for each category.
 */
@Slf4j
public abstract class AbstractSqlDialect implements SqlDialect {

    @Override
    public String quoteIdentifier(String name) {
        if (name == null) {
            return null;
        }
        char q = quoteChar();
        // A name that arrives already wrapped keeps its exact form: re-wrapping a wrapped
        // name would corrupt it.
        if (name.length() >= 2 && name.charAt(0) == q && name.charAt(name.length() - 1) == q) {
            return name;
        }
        // Any other embedded quote char is doubled, which is how every symmetric-quote
        // product escapes it inside a quoted identifier. Stripping it instead would
        // silently rename the object: a column really named a"b would become ab.
        String escaped = normalizeBareIdentifier(name).replace(String.valueOf(q), "" + q + q);
        return q + escaped + q;
    }

    /**
     * Normalizes an unquoted identifier before it is wrapped. The default keeps the name as
     * reported by the source; dialects whose dictionary folds unquoted names (Oracle folds to
     * upper case) override it so generated DDL/DML lands on the conventional object instead
     * of a quoted, case-sensitive twin.
     */
    protected String normalizeBareIdentifier(String name) {
        return name;
    }

    protected abstract char quoteChar();

    @Override
    public String qualify(String schema, String name) {
        if (schema == null || schema.isBlank()) {
            return quoteIdentifier(name);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(name);
    }

    @Override
    public String mapType(ColumnMeta column, DatabaseType sourceType) {
        String mapped = mapByJdbcType(column, sourceType);
        if (mapped != null) {
            return mapped;
        }
        // PostgreSQL reports uuid/json/jsonb/xml/enums as Types.OTHER and arrays as
        // Types.ARRAY with an internal name like "_int4"; no target can create a column
        // named "_int4", so map both onto portable types instead of passing the raw name.
        if (sourceType != null
                && sourceType.getFamily() == DatabaseType.DialectFamily.POSTGRES
                && (column.getJdbcType() == java.sql.Types.OTHER
                    || column.getJdbcType() == java.sql.Types.ARRAY)) {
            return postgresOtherType(column);
        }
        // Unclassified type. Passing the source spelling through is only safe inside the
        // same product family, where the target's own dictionary knows it. A cross-family
        // pass-through (Oracle BINARY_FLOAT → MySQL, say) is invalid DDL, so those fall back
        // to the portable shape that can hold any value of the unknown type.
        String name = column.getTypeName();
        boolean sameFamily = sourceType != null && sourceType.getFamily() == family();
        if (sameFamily && name != null && !name.isBlank()) {
            log.warn("No type mapping for column {} (type {} / JDBC {}); passing through inside"
                    + " the same product family", column.getName(), name, column.getJdbcType());
            return name;
        }
        String fallback = looksBinary(name) ? blobType() : clobType();
        log.error("No portable mapping for column {} (type {} / JDBC {}); using {} so the"
                + " target DDL stays valid", column.getName(), name, column.getJdbcType(),
                fallback);
        return fallback;
    }

    private static boolean looksBinary(String typeName) {
        if (typeName == null) {
            return false;
        }
        String n = typeName.trim().toLowerCase(java.util.Locale.ROOT);
        return n.contains("binary") || n.contains("blob") || n.contains("image")
                || n.contains("geometry") || n.contains("geography") || n.contains("bytea");
    }

    /** Maps by {@link Types} category; returns null when the category is unknown. */
    protected String mapByJdbcType(ColumnMeta c, DatabaseType sourceType) {
        // A vendor type name or code canonicalizes first: the switch below only knows the
        // portable {@link Types} categories.
        VendorTypeMap.Canonical canonical = VendorTypeMap.canonical(sourceType, c);
        int jdbcType = canonical.jdbcType();
        int size = canonical.size() != null ? canonical.size()
                : (c.getSize() == null ? 0 : c.getSize());

        // JSON keeps its native type on targets that have one (MySQL, PostgreSQL) and
        // degrades to text elsewhere.
        if (VendorTypeMap.isJsonTypeName(c.getTypeName())) {
            return jsonType();
        }

        switch (jdbcType) {
            case Types.BIT: {
                // BIT(1) is a boolean; MySQL's BIT(n>1) is an n-bit UNSIGNED integer the
                // driver returns as a byte array, so it needs an integer target (mapping
                // it to BOOLEAN parked baselines in the v1.7.1 docker matrix). Width
                // bands follow the representable range; BIT(64) needs an exact decimal
                // because 2^64-1 overflows signed BIGINT.
                Integer bitWidth = c.getSize();
                if (bitWidth != null && bitWidth > 1) {
                    if (bitWidth <= 8) {
                        return smallIntType();
                    }
                    if (bitWidth <= 16) {
                        return intType();
                    }
                    if (bitWidth <= 63) {
                        return bigIntType();
                    }
                    return decimalType(c, sourceType);
                }
                return booleanType();
            }
            case Types.BOOLEAN:
                return booleanType();
            case Types.TINYINT:
                return tinyIntType();
            case Types.SMALLINT:
                return smallIntType();
            case Types.INTEGER:
                return intType();
            case Types.BIGINT:
                // Connector/J reports BIGINT UNSIGNED as Types.BIGINT, TYPE_NAME
                // "BIGINT UNSIGNED", size 20: its top value 2^64-1 overflows every signed
                // BIGINT target, so non-MySQL targets widen to an exact 20-digit decimal;
                // MySQL-family targets keep the native unsigned type.
                if (isMysqlFamilyUnsigned(c, sourceType)) {
                    return unsignedBigIntType();
                }
                return bigIntType();
            case Types.REAL:
                return floatType();
            case Types.FLOAT:
            case Types.DOUBLE:
                return doubleType();
            case Types.NUMERIC:
            case Types.DECIMAL:
                // Raw metadata is passed through: distinguishing "scale 0" from "scale not
                // reported" is what keeps an unconstrained NUMBER from losing its fraction,
                // and clamping here would erase that distinction.
                return decimalType(c, sourceType);
            case Types.CHAR:
            case Types.NCHAR:
                return charType(Math.max(size, 1));
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                return varcharType(size);
            case Types.DATE:
                if (isMysqlFamilyYear(c, sourceType)) {
                    // Connector/J reports YEAR as JDBC DATE, but row values are integer
                    // years and a DATE target cannot receive them. MySQL-family targets
                    // keep native YEAR; everyone else stores the year in a smallint.
                    return family() == DatabaseType.DialectFamily.MYSQL
                            ? "YEAR" : smallIntType();
                }
                return dateTypeFor(c, sourceType);
            case Types.TIME:
                return timeType();
            case Types.TIME_WITH_TIMEZONE:
                return timeTzType();
            case Types.TIMESTAMP:
                return timestampType();
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return timestampTzType();
            case Types.BINARY:
            case Types.VARBINARY:
                return binaryType(size);
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return blobType();
            case Types.CLOB:
            case Types.NCLOB:
                return clobType();
            default:
                return null;
        }
    }

    /** Target JSON column type; dialects without native JSON keep JSON as a character large
     *  object, which preserves the text. */
    protected String jsonType() {
        return clobType();
    }

    /** Maps a PostgreSQL Types.OTHER column onto types the target can create. */
    protected String postgresOtherType(ColumnMeta column) {
        String name = column.getTypeName();
        if (name == null) {
            return clobType();
        }
        switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "uuid":
                return varcharType(36);
            case "json":
            case "jsonb":
                return jsonType();
            default:
                // xml, enums and PG arrays (type names like "_int4") move as text.
                return clobType();
        }
    }

    protected int maxNumericPrecision() {
        return 38;
    }

    protected int defaultNumericPrecision() {
        return 38;
    }

    /**
     * Fractional digits reserved when the source reports no precision <em>and</em> no usable
     * scale. Zero would be wrong: an unconstrained numeric can hold a fraction, and a
     * DECIMAL(p,0) target truncates it on every insert without raising an error.
     */
    protected int unconstrainedNumericScale() {
        return Math.min(10, defaultNumericPrecision() / 2);
    }

    /**
     * MySQL-family YEAR columns reach us mislabelled as JDBC DATE (Connector/J metadata
     * quirk), even though the type name stays "YEAR" and the row values are integers.
     */
    private static boolean isMysqlFamilyYear(ColumnMeta c, DatabaseType sourceType) {
        return sourceType != null
                && sourceType.getFamily() == DatabaseType.DialectFamily.MYSQL
                && c.getTypeName() != null
                && "YEAR".equalsIgnoreCase(c.getTypeName().trim());
    }

    /**
     * Maps a source DATE column.
     *
     * <p>In the Oracle family (Oracle itself, 达梦 DM, 崖山 YashanDB) DATE is not a date: it
     * carries hours, minutes and seconds. Sending it to a date-only target type therefore
     * drops the time silently on every row. Whether the problem is reachable at all depends
     * on the driver — ojdbc may report such a column as either {@link Types#DATE} or
     * {@link Types#TIMESTAMP} — so this widens defensively and is a no-op when the driver
     * already reports TIMESTAMP.
     *
     * <p>The widening is skipped when the target is itself in the Oracle family, because
     * there DATE already means the same thing and rewriting it to TIMESTAMP would change
     * date-arithmetic semantics for no benefit. That leaves Oracle → 达梦 (DM) unwidened;
     * DM's DATE precision is not verified here, so confirm it before relying on that path.
     */
    protected String dateTypeFor(ColumnMeta c, DatabaseType sourceType) {
        if (sourceType != null
                && sourceType.getFamily() == DatabaseType.DialectFamily.ORACLE
                && family() != DatabaseType.DialectFamily.ORACLE) {
            return timestampType();
        }
        return dateType();
    }

    // --- Type names each dialect must supply -------------------------------------------

    protected abstract String booleanType();

    protected abstract String tinyIntType();

    protected abstract String smallIntType();

    protected abstract String intType();

    protected abstract String bigIntType();

    /** Target type for MySQL-family {@code BIGINT UNSIGNED} (holds up to 2^64-1). Most
     *  products express that exact range as DECIMAL(20,0); MySQL-family targets override. */
    protected String unsignedBigIntType() {
        return "DECIMAL(20,0)";
    }

    /** Connector/J (and MariaDB driver) flag unsigned bigint only through the type name:
     *  JDBC type stays BIGINT and size becomes 20. The name must name BIGINT explicitly —
     *  INT UNSIGNED also arrives as Types.BIGINT, but a signed BIGINT holds its full range. */
    protected static boolean isMysqlFamilyUnsigned(ColumnMeta c, DatabaseType sourceType) {
        if (sourceType == null
                || sourceType.getFamily() != DatabaseType.DialectFamily.MYSQL
                || c.getJdbcType() != Types.BIGINT
                || c.getTypeName() == null) {
            return false;
        }
        String name = c.getTypeName().trim().toUpperCase(Locale.ROOT);
        return name.startsWith("BIGINT") && name.contains("UNSIGNED");
    }

    protected abstract String floatType();

    protected abstract String doubleType();

    protected abstract String dateType();

    protected abstract String timeType();

    /** Target type for TIME WITH TIME ZONE. Dialects without a zone-bearing time type
     *  (MySQL, SQL Server, DB2) fall back to the plain time type and lose the offset. */
    protected String timeTzType() {
        return timeType();
    }

    protected abstract String timestampType();

    protected String timestampTzType() {
        return timestampType();
    }

    protected abstract String blobType();

    protected abstract String clobType();

    protected String charType(int size) {
        if (size > maxCharLength()) {
            // A fixed-width source wider than this product's CHAR ceiling cannot keep its
            // declared width. Narrowing it silently produces a column that rejects the very
            // rows it was created for, so promote to the width-preserving VARCHAR/LOB form
            // instead — the padding semantics are lost either way once the ceiling is hit.
            return varcharType(size);
        }
        return "CHAR(" + size + ")";
    }

    /**
     * A VARCHAR of the given length, promoted to a LOB type when the source length exceeds
     * what this dialect allows inline.
     */
    protected String varcharType(int size) {
        if (size <= 0 || size > maxVarcharLength()) {
            return clobType();
        }
        return "VARCHAR(" + size + ")";
    }

    protected String binaryType(int size) {
        if (size <= 0 || size > maxVarcharLength()) {
            return blobType();
        }
        return "VARBINARY(" + size + ")";
    }

    /**
     * Maps a NUMERIC/DECIMAL column, treating "not reported" and "zero" as different answers.
     *
     * <p>A previous form of this method clamped the precision before deciding, which made its
     * own {@code precision <= 0} branch unreachable and left every unconstrained numeric as
     * {@code DECIMAL(38,0)} — silently truncating fractions. The distinction now survives:
     *
     * <ul>
     *   <li>precision and scale both usable → copied through</li>
     *   <li>precision missing, scale usable → widest precision, source scale</li>
     *   <li>both missing → widest precision with {@link #unconstrainedNumericScale()}
     *       fractional digits, so a fraction is preserved rather than rounded away</li>
     * </ul>
     */
    protected String decimalType(ColumnMeta c, DatabaseType sourceType) {
        Integer rawPrecision = c.getSize();
        Integer rawScale = c.getDecimalDigits();

        // "Declared" and "representable" are different questions, and conflating them loses
        // data: a NUMBER(38,4) going to DB2 has a precision the source stated plainly, it is
        // just wider than DB2's 31-digit ceiling. That needs clamping, not the unconstrained
        // treatment below -- which would discard the declared scale of 4.
        boolean precisionDeclared = rawPrecision != null && rawPrecision > 0;
        // Oracle marks an unconstrained NUMBER with scale -127, and some drivers report null
        // instead. Either way the value may have a fraction.
        boolean scaleKnown = rawScale != null && rawScale >= 0;

        if (scaleKnown && isUnconstrainedOracleNumber(c, sourceType, precisionDeclared)) {
            // Drivers also report this case as a plain 0, which is indistinguishable from a
            // genuine integer NUMBER(p,0) except by the absent precision.
            scaleKnown = false;
        }

        if (!precisionDeclared && !scaleKnown) {
            String mapped = "DECIMAL(" + defaultNumericPrecision() + ","
                    + unconstrainedNumericScale() + ")";
            log.warn("Column {} has an unconstrained {} (precision {}, scale {}); mapping to "
                            + "{} so a fractional value is not truncated. Constrain the source "
                            + "type, or set a DDL override, if a different precision is wanted.",
                    c.getName(), c.getTypeName(), rawPrecision, rawScale, mapped);
            return mapped;
        }

        int p = precisionDeclared ? Math.min(rawPrecision, maxNumericPrecision()) : defaultNumericPrecision();
        int s = scaleKnown ? Math.max(Math.min(rawScale, p), 0) : 0;
        return "DECIMAL(" + p + "," + s + ")";
    }

    /**
     * True for an Oracle-family NUMBER (Oracle, 达梦 DM, 崖山 YashanDB) declared with neither
     * precision nor scale, which behaves as a floating-point decimal rather than an integer.
     */
    private boolean isUnconstrainedOracleNumber(ColumnMeta c, DatabaseType sourceType,
                                                boolean precisionDeclared) {
        return !precisionDeclared
                && sourceType != null
                && sourceType.getFamily() == DatabaseType.DialectFamily.ORACLE
                && c.getTypeName() != null
                && c.getTypeName().toUpperCase().startsWith("NUMBER");
    }

    protected int maxVarcharLength() {
        return 65535;
    }

    protected int maxCharLength() {
        return 255;
    }

    // --- DDL assembly -------------------------------------------------------------------

    @Override
    public String getCreateTableSql(TableMeta table, String targetSchema, String targetTable,
                                    DatabaseType sourceType) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(qualify(targetSchema, targetTable)).append(" (\n");

        List<String> defs = new ArrayList<>();
        Map<String, String> typeOverrides = createColumnTypeOverrides(table, sourceType);
        for (ColumnMeta col : table.getColumns()) {
            defs.add("  " + columnDefinition(col, sourceType, typeOverrides.get(keyOf(col))));
        }
        if (table.hasPrimaryKey()) {
            String pkCols = table.getPrimaryKeys().stream()
                    .map(this::quoteIdentifier)
                    .collect(Collectors.joining(", "));
            defs.add("  PRIMARY KEY (" + pkCols + ")");
        }
        String extraConstraint = additionalTableConstraint(table, targetTable);
        if (extraConstraint != null && !extraConstraint.isBlank()) {
            defs.add("  " + extraConstraint);
        }
        sb.append(String.join(",\n", defs));
        sb.append("\n)");
        sb.append(createTableSuffix(table));
        return sb.toString();
    }

    /** One column's definition inside CREATE TABLE. */
    protected String columnDefinition(ColumnMeta col, DatabaseType sourceType) {
        return columnDefinition(col, sourceType, null);
    }

    /**
     * One column's definition, with an optional target type chosen for the whole table rather
     * than from the column alone (MySQL's row-size budget is the one user).
     */
    protected String columnDefinition(ColumnMeta col, DatabaseType sourceType,
                                      String typeOverride) {
        StringBuilder sb = new StringBuilder();
        sb.append(quoteIdentifier(col.getName())).append(' ');

        String type;
        if (col.isAutoIncrement() && autoIncrementColumnType(col, sourceType) != null) {
            type = autoIncrementColumnType(col, sourceType);
        } else if (typeOverride != null) {
            type = typeOverride;
        } else {
            type = mapType(col, sourceType);
        }
        sb.append(type);

        // The auto-increment clause must follow the type IMMEDIATELY. T-SQL rejected the
        // old "type NOT NULL IDENTITY(1,1)" order — IDENTITY has to precede NOT NULL;
        // MySQL accepts AUTO_INCREMENT in this position too.
        if (col.isAutoIncrement()) {
            String clause = autoIncrementClause();
            if (clause != null && !clause.isEmpty()) {
                sb.append(' ').append(clause);
            }
        }

        // A primary key column is implicitly NOT NULL; stating it is harmless and clearer.
        // DB2 is the exception: its grammar puts NOT NULL BEFORE the identity clause, and an
        // identity column is implicitly NOT NULL anyway, so it simply omits the tail.
        String nullClause = null;
        if (!col.isNullable() && (!col.isAutoIncrement() || notNullAfterIdentityClause())) {
            nullClause = " NOT NULL";
        }

        String def = renderDefaultValue(col);
        if (def != null && !defaultClauseAllowed(type)) {
            // Losing a default is recoverable; a CREATE TABLE the target rejects is not.
            log.debug("Skipping the default of column {}: {} does not accept one",
                    col.getName(), type);
            def = null;
        }
        String defaultClause = def == null ? null : " DEFAULT " + def;

        // Oracle's column grammar is name type [DEFAULT expr] [inline constraint ...]: a
        // DEFAULT after NOT NULL is a syntax error (ORA-00907). MySQL, PostgreSQL, T-SQL and
        // DB2 all accept DEFAULT in either position, so their order is left unchanged.
        if (defaultBeforeNotNull()) {
            if (defaultClause != null) {
                sb.append(defaultClause);
            }
            if (nullClause != null) {
                sb.append(nullClause);
            }
        } else {
            if (nullClause != null) {
                sb.append(nullClause);
            }
            if (defaultClause != null) {
                sb.append(defaultClause);
            }
        }
        sb.append(columnCommentClause(col));
        return sb.toString();
    }

    /**
     * Whether a {@code DEFAULT} clause is legal on this already-rendered target type.
     *
     * <p>Only MySQL needs this: it rejects a default on TEXT/BLOB (error 1101 on 5.7; 8.0
     * takes an expression default only), which a row-size narrowing can turn a column into.
     * Every other product accepts a default on its LOB types.
     */
    protected boolean defaultClauseAllowed(String targetType) {
        return true;
    }

    /**
     * Target column types chosen for the whole table instead of per column, keyed by
     * {@link #keyOf(ColumnMeta)}. Empty when the target has no whole-table constraint.
     */
    protected Map<String, String> createColumnTypeOverrides(TableMeta table,
                                                            DatabaseType sourceType) {
        return java.util.Collections.emptyMap();
    }

    /** Case-folded key used by {@link #createColumnTypeOverrides}. */
    protected static String keyOf(ColumnMeta col) {
        return col.getName() == null ? "" : col.getName().toUpperCase(java.util.Locale.ROOT);
    }

    /** Trailing comment clause of a column definition; empty for targets without one. */
    protected String columnCommentClause(ColumnMeta col) {
        return "";
    }

    /**
     * A single type token that already implies identity/serial behaviour (e.g. PostgreSQL
     * {@code BIGSERIAL}); null when the dialect uses a separate clause instead.
     */
    protected String autoIncrementColumnType(ColumnMeta col, DatabaseType sourceType) {
        return null;
    }

    /** A trailing clause such as {@code AUTO_INCREMENT} or {@code IDENTITY(1,1)}. */
    protected String autoIncrementClause() {
        return null;
    }

    protected String createTableSuffix(TableMeta table) {
        return "";
    }

    /**
     * An extra inline table constraint (e.g. MySQL's required key on an AUTO_INCREMENT
     * column), or {@code null} when the dialect needs none.
     */
    protected String additionalTableConstraint(TableMeta table, String targetTable) {
        return null;
    }

    /**
     * Whether {@code NOT NULL} may be emitted after the auto-increment/identity clause.
     * T-SQL and MySQL require/accept that order; DB2 rejects it (SQL0104) and its identity
     * columns are already implicitly NOT NULL.
     */
    protected boolean notNullAfterIdentityClause() {
        return true;
    }

    /**
     * Whether {@code DEFAULT} must be emitted before {@code NOT NULL}. Oracle's grammar is
     * {@code column_name data_type [DEFAULT expr] [inline_constraint ...]}, so the MySQL order
     * produces ORA-00907; every other supported target accepts either order.
     */
    protected boolean defaultBeforeNotNull() {
        return false;
    }

    @Override
    public String renderDefaultValue(ColumnMeta column) {
        String def = column.getDefaultValue();
        if (def == null) {
            return null;
        }
        String trimmed = def.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Auto-increment columns carry a sequence default that must not be copied.
        if (column.isAutoIncrement()) {
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.equals("NULL")) {
            return "NULL";
        }
        // Already a literal or a function call the target is likely to understand.
        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            return normalizeQuotedDefault(trimmed);
        }
        if (isCurrentTimestampDefault(upper)) {
            return currentTimestampFunction();
        }
        if (isNumeric(trimmed)) {
            return trimmed;
        }
        if (upper.equals("TRUE") || upper.equals("FALSE")) {
            return renderBooleanDefault(upper.equals("TRUE"));
        }
        // A product-specific expression: skip rather than emit something the target rejects.
        // Losing a default is recoverable; a failed CREATE TABLE blocks the whole table.
        log.debug("Skipping non-portable default for column {}: {}", column.getName(), trimmed);
        return null;
    }

    /** Converts a double-quoted default (PostgreSQL style) into single quotes. */
    protected String normalizeQuotedDefault(String value) {
        String v = value;
        // PostgreSQL appends a cast, e.g. 'active'::character varying. For a quoted literal
        // only a cast that follows its closing quote may be stripped: the literal itself can
        // contain "::", and cutting at the first occurrence leaves an unterminated string.
        int cast = v.startsWith("'") ? castAfterLiteral(v) : v.indexOf("::");
        if (cast > 0) {
            v = v.substring(0, cast);
        }
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = "'" + v.substring(1, v.length() - 1).replace("'", "''") + "'";
        }
        return v;
    }

    /**
     * Index of the {@code ::} that starts a cast following a SQL string literal, or -1 when
     * there is none. Doubled quotes inside the literal are skipped so an apostrophe cannot be
     * mistaken for its end.
     */
    private static int castAfterLiteral(String value) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) != '\'') {
                continue;
            }
            if (i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                i++; // escaped quote inside the literal
                continue;
            }
            return value.startsWith("::", i + 1) ? i + 1 : -1;
        }
        return -1;
    }

    protected boolean isCurrentTimestampDefault(String upper) {
        return upper.startsWith("CURRENT_TIMESTAMP")
                || upper.startsWith("NOW()")
                || upper.startsWith("GETDATE()")
                || upper.startsWith("SYSDATE")
                || upper.startsWith("LOCALTIMESTAMP")
                || upper.startsWith("SYSTIMESTAMP");
    }

    protected String currentTimestampFunction() {
        return "CURRENT_TIMESTAMP";
    }

    protected String renderBooleanDefault(boolean value) {
        return value ? "1" : "0";
    }

    protected boolean isNumeric(String s) {
        // Double.parseDouble also accepts NaN, Infinity and hex doubles (0x1p4); none of
        // them are legal DEFAULT literals on any target, so reject them up front.
        String trimmed = s.trim();
        if (!trimmed.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) {
            return false;
        }
        try {
            return Double.isFinite(Double.parseDouble(trimmed));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public String getAddColumnSql(String schema, String table, ColumnMeta column,
                                  DatabaseType sourceType) {
        return "ALTER TABLE " + qualify(schema, table) + " ADD COLUMN "
                + columnDefinition(column, sourceType);
    }

    @Override
    public String getDropColumnSql(String schema, String table, String columnName) {
        return "ALTER TABLE " + qualify(schema, table) + " DROP COLUMN " + quoteIdentifier(columnName);
    }

    @Override
    public String getCreateIndexSql(IndexMeta index, String schema, String table) {
        String cols = index.getColumns().stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        return "CREATE " + (index.isUnique() ? "UNIQUE " : "") + "INDEX "
                + quoteIdentifier(indexNameFor(index, table)) + " ON "
                + qualify(schema, table) + " (" + cols + ")";
    }

    /**
     * Index names are schema-scoped in some products and table-scoped in others. Prefixing
     * with the table keeps names unique when the target scopes them per schema.
     */
    protected String indexNameFor(IndexMeta index, String table) {
        String name = index.getName();
        if (!indexNamesAreGlobal()) {
            return truncateIdentifier(name);
        }
        if (name.toLowerCase().contains(table.toLowerCase())) {
            return truncateIdentifier(name);
        }
        return truncateIdentifier(table + "_" + name);
    }

    /** True when index names must be unique across the schema, not just the table. */
    protected boolean indexNamesAreGlobal() {
        return false;
    }

    protected String truncateIdentifier(String name) {
        int max = maxIdentifierLength();
        if (name == null || name.length() <= max) {
            return name;
        }
        // Keep a hash suffix so two truncated names do not collide.
        String hash = Integer.toHexString(name.hashCode());
        return name.substring(0, max - hash.length() - 1) + "_" + hash;
    }

    protected int maxIdentifierLength() {
        return 63;
    }

    @Override
    public String getDropIndexSql(IndexMeta index, String schema, String table) {
        return "DROP INDEX " + quoteIdentifier(indexNameFor(index, table));
    }

    @Override
    public String getDropTableSql(String schema, String table) {
        return "DROP TABLE " + qualify(schema, table);
    }

    @Override
    public String getDropViewSql(String schema, String view) {
        return "DROP VIEW " + qualify(schema, view);
    }

    @Override
    public String getDropProcedureSql(String schema, String procedure, boolean isFunction) {
        return "DROP " + (isFunction ? "FUNCTION " : "PROCEDURE ") + qualify(schema, procedure);
    }

    @Override
    public String getDeleteByPkSql(String schema, String table, List<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            throw new IllegalArgumentException("delete.requires.pk");
        }
        String where = pkColumns.stream()
                .map(c -> quoteIdentifier(c) + " = ?")
                .collect(Collectors.joining(" AND "));
        return "DELETE FROM " + qualify(schema, table) + " WHERE " + where;
    }

    @Override
    public String getTruncateSql(String schema, String table) {
        return "TRUNCATE TABLE " + qualify(schema, table);
    }

    @Override
    public String getPaginationSql(String baseSql, long offset, int limit) {
        // SQL:2008 form, supported by most modern products.
        return baseSql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    public String getDisableConstraintsSql() {
        return null;
    }

    @Override
    public String getEnableConstraintsSql() {
        return null;
    }

    @Override
    public boolean supportsAlterColumnType() {
        return true;
    }

    /** Default bind order: one value per column, in the order given. */
    @Override
    public List<String> upsertBindOrder(List<String> columns, List<String> pkColumns) {
        return new ArrayList<>(columns);
    }

    /** Non-key columns, i.e. the ones an upsert should overwrite. */
    protected List<String> nonKeyColumns(List<String> columns, List<String> pkColumns) {
        List<String> pkUpper = pkColumns.stream().map(String::toUpperCase).collect(Collectors.toList());
        return columns.stream()
                .filter(c -> !pkUpper.contains(c.toUpperCase()))
                .collect(Collectors.toList());
    }

    protected String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /**
     * One placeholder per column, decorated per the target column's JDBC type where a
     * dialect needs it ({@link #placeholderFor(Integer)}). A null/empty type map yields
     * plain question marks.
     */
    protected String typedPlaceholders(List<String> columns,
                                       java.util.Map<String, Integer> columnJdbcTypes) {
        if (columnJdbcTypes == null || columnJdbcTypes.isEmpty()) {
            return placeholders(columns.size());
        }
        return columns.stream()
                .map(c -> placeholderFor(columnJdbcTypes.get(c)))
                .collect(Collectors.joining(", "));
    }

    /**
     * Placeholder for a bind that lands in a column of this JDBC type. Plain {@code ?}
     * for every type by default; PostgreSQL-family dialects cast TIME binds (the sync
     * sends MySQL TIME values as strings because that domain can exceed 24h).
     */
    protected String placeholderFor(Integer jdbcType) {
        return "?";
    }

    protected String quotedList(List<String> names) {
        return names.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
    }
}
