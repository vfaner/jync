package com.qqmu.jync.service.converter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.qqmu.jync.model.DatabaseType;

import lombok.extern.slf4j.Slf4j;

/**
 * Best-effort textual translation of SQL bodies (views and routines) between products.
 *
 * <p>This handles the mechanical differences that regex substitution can address reliably:
 * function names, string concatenation, and the small set of syntax markers that differ per
 * product. It deliberately does <em>not</em> attempt to translate procedural control flow —
 * PL/SQL, T-SQL and PL/pgSQL differ structurally, and a partial rewrite is worse than an
 * honest failure.
 *
 * <p>When a body cannot be translated confidently the conversion is still attempted and the
 * target's error is surfaced against the specific object, so the user can supply a manual
 * override through {@link com.qqmu.jync.dto.SyncConfig#getDdlOverrides()}.
 */
@Component
@Slf4j
public class SqlBodyConverter {

    /**
     * Date/number formatting functions. Present in more than one product under the same name
     * but with incompatible format strings, so they are reported rather than rewritten.
     */
    private static final Pattern FORMAT_SENSITIVE = Pattern.compile(
            "(?i)\\b(TO_CHAR|TO_DATE|TO_TIMESTAMP|TO_NUMBER|DATE_FORMAT|STR_TO_DATE|FORMAT)\\s*\\(");

    /** Function name translations, keyed by target family. */
    private final Map<DatabaseType.DialectFamily, Map<String, String>> functionMaps = new LinkedHashMap<>();

    /** Maps the type name inside a CAST/CONVERT; null in the legacy no-arg test wiring. */
    private final RoutineTypeMapper routineTypeMapper;
    private final SqlDialectFactory dialectFactory;

    @Autowired
    public SqlBodyConverter(RoutineTypeMapper routineTypeMapper,
                            SqlDialectFactory dialectFactory) {
        this.routineTypeMapper = routineTypeMapper;
        this.dialectFactory = dialectFactory;
        buildFunctionMaps();
    }

    /** Convenience constructor for tests that do not exercise CAST/CONVERT type mapping. */
    public SqlBodyConverter() {
        this.routineTypeMapper = null;
        this.dialectFactory = null;
        buildFunctionMaps();
    }

    private void buildFunctionMaps() {
        Map<String, String> toMysql = new LinkedHashMap<>();
        toMysql.put("NVL", "IFNULL");
        toMysql.put("SYSDATE", "NOW()");
        // SYSTIMESTAMP carries microsecond precision; plain NOW() would silently drop it.
        toMysql.put("SYSTIMESTAMP", "NOW(6)");
        toMysql.put("GETDATE", "NOW()");
        toMysql.put("SUBSTR", "SUBSTRING");
        // SQL Server LEN counts characters; MySQL LENGTH counts bytes. CHAR_LENGTH matches.
        toMysql.put("LEN", "CHAR_LENGTH");
        toMysql.put("ISNULL", "IFNULL");
        toMysql.put("REPLICATE", "REPEAT");
        toMysql.put("RANDOM", "RAND");
        toMysql.put("EOMONTH", "LAST_DAY");
        toMysql.put("CHARINDEX", "LOCATE");
        toMysql.put("STRPOS", "INSTR");
        toMysql.put("POW", "POWER");
        toMysql.put("UCASE", "UPPER");
        toMysql.put("LCASE", "LOWER");
        toMysql.put("DATALENGTH", "LENGTH");
        functionMaps.put(DatabaseType.DialectFamily.MYSQL, toMysql);

        Map<String, String> toOracle = new LinkedHashMap<>();
        toOracle.put("IFNULL", "NVL");
        toOracle.put("ISNULL", "NVL");
        toOracle.put("NOW", "SYSDATE");
        toOracle.put("GETDATE", "SYSDATE");
        toOracle.put("CURDATE", "TRUNC(SYSDATE)");
        toOracle.put("SUBSTRING", "SUBSTR");
        toOracle.put("LEN", "LENGTH");
        toOracle.put("CEILING", "CEIL");
        toOracle.put("EOMONTH", "LAST_DAY");
        toOracle.put("POW", "POWER");
        toOracle.put("UCASE", "UPPER");
        toOracle.put("LCASE", "LOWER");
        toOracle.put("DATALENGTH", "LENGTHB");
        functionMaps.put(DatabaseType.DialectFamily.ORACLE, toOracle);

        Map<String, String> toPostgres = new LinkedHashMap<>();
        toPostgres.put("NVL", "COALESCE");
        toPostgres.put("IFNULL", "COALESCE");
        toPostgres.put("ISNULL", "COALESCE");
        toPostgres.put("SYSDATE", "CURRENT_TIMESTAMP");
        toPostgres.put("SYSTIMESTAMP", "CURRENT_TIMESTAMP");
        toPostgres.put("GETDATE", "NOW()");
        toPostgres.put("LEN", "LENGTH");
        toPostgres.put("REPLICATE", "REPEAT");
        toPostgres.put("POW", "POWER");
        toPostgres.put("UCASE", "UPPER");
        toPostgres.put("LCASE", "LOWER");
        toPostgres.put("DATALENGTH", "OCTET_LENGTH");
        toPostgres.put("CURDATE", "CURRENT_DATE");
        functionMaps.put(DatabaseType.DialectFamily.POSTGRES, toPostgres);

        Map<String, String> toSqlServer = new LinkedHashMap<>();
        toSqlServer.put("NVL", "ISNULL");
        toSqlServer.put("IFNULL", "ISNULL");
        toSqlServer.put("SYSDATE", "GETDATE()");
        toSqlServer.put("SYSTIMESTAMP", "SYSDATETIME()");
        toSqlServer.put("NOW", "GETDATE()");
        toSqlServer.put("LENGTH", "LEN");
        toSqlServer.put("SUBSTR", "SUBSTRING");
        toSqlServer.put("CEIL", "CEILING");
        toSqlServer.put("REPEAT", "REPLICATE");
        toSqlServer.put("RANDOM", "RAND");
        toSqlServer.put("LAST_DAY", "EOMONTH");
        toSqlServer.put("LOCATE", "CHARINDEX");
        toSqlServer.put("POW", "POWER");
        toSqlServer.put("UCASE", "UPPER");
        toSqlServer.put("LCASE", "LOWER");
        functionMaps.put(DatabaseType.DialectFamily.SQLSERVER, toSqlServer);

        Map<String, String> toDb2 = new LinkedHashMap<>();
        toDb2.put("NVL", "COALESCE");
        toDb2.put("IFNULL", "COALESCE");
        toDb2.put("ISNULL", "COALESCE");
        toDb2.put("SYSDATE", "CURRENT TIMESTAMP");
        toDb2.put("GETDATE", "CURRENT TIMESTAMP");
        toDb2.put("NOW", "CURRENT TIMESTAMP");
        toDb2.put("CEILING", "CEIL");
        toDb2.put("REPLICATE", "REPEAT");
        toDb2.put("RANDOM", "RAND");
        toDb2.put("EOMONTH", "LAST_DAY");
        toDb2.put("CHARINDEX", "LOCATE");
        toDb2.put("STRPOS", "POSSTR");
        toDb2.put("POW", "POWER");
        toDb2.put("DATALENGTH", "LENGTH");
        functionMaps.put(DatabaseType.DialectFamily.DB2, toDb2);

        // HANA's own spellings; each replacement was checked against HANA Express 2.00.082.
        Map<String, String> toHana = new LinkedHashMap<>();
        toHana.put("NVL", "COALESCE");
        toHana.put("IFNULL", "COALESCE");
        toHana.put("ISNULL", "COALESCE");
        toHana.put("SYSDATE", "CURRENT_TIMESTAMP");
        toHana.put("SYSTIMESTAMP", "CURRENT_TIMESTAMP");
        toHana.put("GETDATE", "CURRENT_TIMESTAMP");
        toHana.put("NOW", "CURRENT_TIMESTAMP");
        toHana.put("LEN", "LENGTH");
        toHana.put("CEILING", "CEIL");
        toHana.put("EOMONTH", "LAST_DAY");
        toHana.put("STRPOS", "INSTR");
        toHana.put("POW", "POWER");
        toHana.put("UCASE", "UPPER");
        toHana.put("LCASE", "LOWER");
        toHana.put("DATALENGTH", "LENGTHB");
        functionMaps.put(DatabaseType.DialectFamily.HANA, toHana);
    }

    /**
     * A converted body plus the reasons automatic conversion must not be applied.
     *
     * <p>A blocking reason means the generated SQL would compile on the target but behave
     * differently than on the source (a silent wrong result). The caller is expected to refuse
     * the apply and surface the reason; the converted {@code sql} is still returned verbatim
     * for read-only previews.
     */
    public record ConversionResult(String sql, java.util.List<String> blockingReasons) {

        public ConversionResult {
            blockingReasons = java.util.List.copyOf(blockingReasons);
        }

        public boolean blocked() {
            return !blockingReasons.isEmpty();
        }
    }

    /**
     * Rewrites a SQL body from the source product's dialect into the target's.
     *
     * @return the converted SQL plus blocking reasons, or the input unchanged when source and
     *         target are the same family
     */
    public ConversionResult convert(String sql, DatabaseType sourceType, DatabaseType targetType) {
        return convert(sql, sourceType, targetType, false);
    }

    /**
     * Converts a stored procedure or function body. Unlike a view, a cross-family procedural
     * routine body is not a single query and is blocked instead of being mechanically mangled.
     */
    public ConversionResult convertProcedure(String sql, DatabaseType sourceType,
                                             DatabaseType targetType) {
        return convert(sql, sourceType, targetType, true);
    }

    private ConversionResult convert(String sql, DatabaseType sourceType, DatabaseType targetType,
                                     boolean routine) {
        if (sql == null || sql.isBlank()) {
            return new ConversionResult(sql, java.util.List.of());
        }
        DatabaseType.DialectFamily from = sourceType == null
                ? DatabaseType.DialectFamily.GENERIC : sourceType.getFamily();
        DatabaseType.DialectFamily to = targetType == null
                ? DatabaseType.DialectFamily.GENERIC : targetType.getFamily();
        if (from == to) {
            return new ConversionResult(sql, java.util.List.of());
        }

        // DECODE/NVL2 (Oracle family) become searched CASE expressions first: their arguments
        // can contain literals, so the call has to be extracted with a quote-aware walk rather
        // than through the literal-free code segments. An untranslatable NULL-search DECODE is
        // left intact and reported as a block.
        ConditionalRewrite conditional = rewriteOracleConditionals(sql, from, to);
        String result = conditional.sql();
        warnOnFormatSensitiveFunctions(result, from, to);
        result = convertIdentifierQuotes(result, from, to);
        result = convertConcatenation(result, from, to);
        result = convertMysqlIsNullPredicate(result, from, to);
        result = convertFunctions(result, from, to);
        CastRewrite cast = rewriteCastAndConvert(result, sourceType, targetType);
        result = cast.sql();
        result = convertLimitClause(result, from, to);
        result = convertDualTable(result, from, to);
        result = convertSetDifference(result, from, to);
        result = convertGroupingExtensions(result, from, to);

        java.util.List<String> blocking = new java.util.ArrayList<>();
        result = convertBooleanLiterals(result, from, to, blocking);
        // '||' -> MySQL would be read as logical OR: a view/function returning 0/1 instead of
        // the concatenated text, with no error. Block rather than emit it.
        if (to == DatabaseType.DialectFamily.MYSQL
                && containsStringConcatenation(result)) {
            blocking.add("the body uses '||' string concatenation, which MySQL interprets as"
                    + " logical OR; rewrite it with CONCAT() or enable PIPES_AS_CONCAT");
        }
        // SQL Server has no LIMIT at any query depth. The old converter stripped a trailing
        // LIMIT, silently changing the row count; refuse instead and name the alternatives.
        if (to == DatabaseType.DialectFamily.SQLSERVER
                && LIMIT_ANY.matcher(maskNonCode(result, from, to)).find()) {
            blocking.add("the body uses LIMIT row limiting, which SQL Server does not support;"
                    + " rewrite it with TOP or OFFSET ... FETCH");
        }
        // A bare FETCH FIRST n ROWS ONLY is not T-SQL: SQL Server requires ORDER BY ... OFFSET
        // m ROWS FETCH NEXT n ROWS ONLY. Strip the complete OFFSET-FETCH shapes first; any
        // FETCH ... ONLY left over is the unsupported bare form (subqueries included).
        if (to == DatabaseType.DialectFamily.SQLSERVER) {
            String withoutOffsetFetch = maskNonCode(result, from, to).replaceAll(
                    "(?i)\\bOFFSET\\s+\\d+\\s+ROWS?\\s+FETCH\\s+(?:FIRST|NEXT)\\s+\\d+"
                            + "\\s+ROWS?\\s+ONLY", " ");
            if (FETCH_ANY.matcher(withoutOffsetFetch).find()) {
                blocking.add("the body uses a bare FETCH FIRST row limit, which SQL Server"
                        + " accepts only as ORDER BY ... OFFSET 0 ROWS FETCH NEXT n ROWS ONLY;"
                        + " rewrite it with TOP or a complete OFFSET ... FETCH, or provide a"
                        + " DDL override");
            }
        }
        // S3: The PostgreSQL/MySQL JSON access operators (->, ->>, #>, #>>) are shared only by
        // those two families; Oracle/SQL Server/DB2/HANA parse them as unrelated punctuation.
        // They cannot be translated mechanically because the target JSON functions differ, so
        // name the operators and the JSON_VALUE/JSON_QUERY alternatives in the block reason.
        if ((from == DatabaseType.DialectFamily.POSTGRES
                || from == DatabaseType.DialectFamily.MYSQL)
                && (to == DatabaseType.DialectFamily.ORACLE
                || to == DatabaseType.DialectFamily.SQLSERVER
                || to == DatabaseType.DialectFamily.DB2
                || to == DatabaseType.DialectFamily.HANA)) {
            String opsText = maskNonCode(result, from, to);
            List<String> ops = new ArrayList<>();
            if (opsText.contains("#>>")) {
                ops.add("#>>");
                opsText = opsText.replace("#>>", "   ");
            }
            if (opsText.contains("#>")) {
                ops.add("#>");
                opsText = opsText.replace("#>", "  ");
            }
            if (opsText.contains("->>")) {
                ops.add("->>");
                opsText = opsText.replace("->>", "   ");
            }
            if (opsText.contains("->")) {
                ops.add("->");
            }
            if (!ops.isEmpty()) {
                blocking.add("the body uses PostgreSQL/MySQL JSON access operator(s) "
                        + String.join(", ", ops) + ", which the target does not support; rewrite"
                        + " the expression with the target's JSON functions (e.g. JSON_VALUE or"
                        + " JSON_QUERY) or provide a DDL override");
            }
        }
        // B9: MySQL offers neither CUBE nor GROUPING SETS, and the other families' ROLLUP
        // function is the one shape MySQL does accept. Block rather than drop the modifier.
        if (to == DatabaseType.DialectFamily.MYSQL) {
            String maskedGrouping = maskNonCode(result, from, to);
            if (CUBE_ANY.matcher(maskedGrouping).find()) {
                blocking.add("the body uses CUBE grouping, which MySQL does not support; rewrite"
                        + " it with ROLLUP or pre-aggregate the dimensions, or provide a DDL"
                        + " override");
            }
            if (GROUPING_SETS_ANY.matcher(maskedGrouping).find()) {
                blocking.add("the body uses GROUPING SETS, which MySQL does not support; rewrite"
                        + " the grouping or provide a DDL override");
            }
        }
        // EXCEPT ALL cannot become Oracle's MINUS, which is always duplicate-removing: the
        // rename would silently drop duplicate rows. Refuse instead.
        if (to == DatabaseType.DialectFamily.ORACLE
                && EXCEPT_ALL.matcher(maskNonCode(result, from, to)).find()) {
            blocking.add("the body uses EXCEPT ALL, but Oracle only offers MINUS, which removes"
                    + " duplicates; rewrite the query or provide a DDL override");
        }
        String masked = maskNonCode(result, from, to);
        // S1: "<clock function> +/- n" is day arithmetic on Oracle. After the clock-function
        // rename it becomes NOW()-n on MySQL, which coerces the timestamp to a number and
        // subtracts — a view that creates fine and returns a wrong number. On an Oracle target
        // the renames instead produce SYSDATE-n, which is exactly right, so that target is
        // exempt; the other families either error or risk the silent numeric result.
        if (to != DatabaseType.DialectFamily.ORACLE
                && DATE_ARITHMETIC.matcher(masked).find()) {
            blocking.add("the body uses date arithmetic (a date expression plus/minus a number),"
                    + " which the target does not interpret as day arithmetic; rewrite it with"
                    + " DATE_ADD(date, INTERVAL n DAY) or date + INTERVAL 'n' DAY, or provide a"
                    + " DDL override");
        }
        // B1: ROWNUM exists in Oracle, HANA and DB2 only. A simple WHERE ROWNUM <= n could
        // become LIMIT/TOP, but only after real parsing; for now the nested-paging shapes would
        // be rewritten wrong, so refuse and name the alternatives.
        if ((to == DatabaseType.DialectFamily.MYSQL
                || to == DatabaseType.DialectFamily.POSTGRES
                || to == DatabaseType.DialectFamily.SQLSERVER)
                && ROWNUM_ANY.matcher(masked).find()) {
            blocking.add("the body uses the ROWNUM pseudo-column, which the target does not"
                    + " provide; rewrite the row limiting with LIMIT/TOP or OFFSET ... FETCH,"
                    + " or provide a DDL override");
        }
        // B2: TOP n PERCENT and WITH TIES have no LIMIT/FETCH FIRST equivalent on any family.
        if (to != DatabaseType.DialectFamily.SQLSERVER
                && (TOP_PERCENT.matcher(masked).find() || WITH_TIES.matcher(masked).find())) {
            blocking.add("the body uses TOP PERCENT or WITH TIES, which cannot be expressed with"
                    + " the target's row-limiting syntax; rewrite the query or provide a DDL"
                    + " override");
        }
        // B3: Oracle's old-style outer-join marker (+) must become an explicit OUTER JOIN; a
        // textual rewrite cannot identify which table side the marker attaches to.
        if (from == DatabaseType.DialectFamily.ORACLE
                && to != DatabaseType.DialectFamily.ORACLE
                && OLD_OUTER_JOIN.matcher(masked).find()) {
            blocking.add("the body uses Oracle's old-style outer-join marker (+), which the"
                    + " target does not support; rewrite it with an explicit OUTER JOIN or"
                    + " provide a DDL override");
        }
        // S2: MySQL backslash escapes inside string literals change value on targets that read
        // '\' as an ordinary character.
        if (from == DatabaseType.DialectFamily.MYSQL
                && to != DatabaseType.DialectFamily.MYSQL
                && containsBackslashInMysqlLiteral(result)) {
            blocking.add("the body uses MySQL backslash escapes inside a string literal, which"
                    + " the target reads as ordinary backslash characters; rewrite the literal"
                    + " using doubled single quotes and CHR() or provide a DDL override");
        }
        if (conditional.blockingReason() != null) {
            blocking.add(conditional.blockingReason());
        }
        blocking.addAll(cast.reasons());
        // PostgreSQL's :: cast operator is syntax no other family parses. The standards-shaped
        // CAST(...) calls are rewritten above; the operator form cannot be split mechanically
        // (the cast expression may be a full parenthesized expression), so refuse.
        if (from == DatabaseType.DialectFamily.POSTGRES
                && maskNonCode(result, from, to).contains("::")) {
            blocking.add("the body uses the PostgreSQL :: cast operator, which the target"
                    + " cannot parse; rewrite it as a standard CAST(expression AS type) or"
                    + " provide a DDL override");
        }
        if (routine && PROCEDURAL_MARKER.matcher(maskNonCode(result, from, to)).find()) {
            blocking.add("the body uses a procedural routine body (variables, cursors, loops,"
                    + " condition handlers or exception blocks), whose language cannot be"
                    + " translated mechanically across dialect families; rewrite the routine"
                    + " directly for the target or provide a DDL override");
        }
        return new ConversionResult(result, blocking);
    }

    /** Rewritten text plus the block reasons collected while translating CAST/CONVERT. */
    private record CastRewrite(String sql, List<String> reasons) {
    }

    /** IS [NOT] TRUE/FALSE: a real predicate with three-valued-logic NULL behavior. */
    private static final Pattern IS_BOOLEAN = Pattern.compile(
            "(?i)\\bIS\\s+(?:NOT\\s+)?(?:TRUE|FALSE)\\b");

    /**
     * Rewrites the boolean constants {@code TRUE}/{@code FALSE} to {@code 1}/{@code 0} for the
     * two families that store booleans as numbers and lack boolean constants (Oracle and SQL
     * Server). The other families speak the keywords natively.
     *
     * <p>SQL Server cannot express {@code x IS TRUE}, whose NULL treatment differs from
     * {@code x = 1}; when such a predicate is present the whole rewrite is skipped and the
     * object is blocked, so the NULL semantics are not changed silently.
     */
    String convertBooleanLiterals(String sql, DatabaseType.DialectFamily from,
                                  DatabaseType.DialectFamily to, List<String> blocking) {
        if (to != DatabaseType.DialectFamily.ORACLE
                && to != DatabaseType.DialectFamily.SQLSERVER) {
            return sql;
        }
        if (to == DatabaseType.DialectFamily.SQLSERVER
                && IS_BOOLEAN.matcher(maskNonCode(sql, from, to)).find()) {
            blocking.add("the body uses an IS TRUE / IS FALSE predicate, whose NULL treatment"
                    + " (false rather than unknown) cannot be expressed as a bit comparison on"
                    + " SQL Server; rewrite the predicate explicitly or provide a DDL override");
            return sql;
        }
        return applyOutsideLiterals(sql, from, to, code -> code
                .replaceAll("(?i)\\bTRUE\\b", "1")
                .replaceAll("(?i)\\bFALSE\\b", "0"));
    }

    /** Result of the Oracle conditional-function rewrite: the new text and a block reason. */
    private record ConditionalRewrite(String sql, String blockingReason) {
        ConditionalRewrite(String sql) {
            this(sql, null);
        }
    }

    private static final int DECODE = 0;
    private static final int NVL2 = 1;

    private record ConditionalCall(int kind, int start, int open, int close) {}

    /**
     * Rewrites Oracle-family {@code DECODE} and {@code NVL2} calls into standard CASE
     * expressions.
     *
     * <p>Only the Oracle family (Oracle, DM, YashanDB) offers these functions, and a MySQL
     * {@code DECODE} is an unrelated encryption routine, so the rewrite is restricted to
     * Oracle-family sources. A {@code DECODE} that searches for the literal {@code NULL} is
     * left unchanged and reported: Oracle DECODE matches NULL via IS-equality, while
     * {@code CASE expr WHEN NULL} never matches — a silent wrong result.
     */
    ConditionalRewrite rewriteOracleConditionals(String sql,
                                                 DatabaseType.DialectFamily from,
                                                 DatabaseType.DialectFamily to) {
        if (from != DatabaseType.DialectFamily.ORACLE
                || to == DatabaseType.DialectFamily.ORACLE) {
            return new ConditionalRewrite(sql);
        }
        List<ConditionalCall> calls = findConditionalCalls(sql, from);
        if (calls.isEmpty()) {
            return new ConditionalRewrite(sql);
        }
        StringBuilder out = new StringBuilder(sql);
        String blockReason = null;
        for (int idx = calls.size() - 1; idx >= 0; idx--) {
            ConditionalCall call = calls.get(idx);
            List<String> args = splitTopLevelArguments(
                    sql.substring(call.open() + 1, call.close() - 1), from);
            String replacement = null;
            if (call.kind() == DECODE) {
                if (args.size() >= 3) {
                    boolean searchesNull = false;
                    for (int k = 1; k + 1 < args.size(); k += 2) {
                        if (args.get(k).trim().equalsIgnoreCase("NULL")) {
                            searchesNull = true;
                        }
                    }
                    if (searchesNull) {
                        blockReason = "the body uses DECODE with a NULL search value, which a"
                                + " CASE expression cannot match (Oracle DECODE uses"
                                + " IS-equality); rewrite the object with an explicit"
                                + " \"expr IS NULL\" test or provide a DDL override";
                    } else {
                        replacement = buildDecodeCase(args);
                    }
                }
            } else if (call.kind() == NVL2 && args.size() == 3) {
                replacement = "CASE WHEN " + args.get(0).trim() + " IS NOT NULL THEN "
                        + args.get(1).trim() + " ELSE " + args.get(2).trim() + " END";
            }
            if (replacement != null) {
                out.replace(call.start(), call.close(), replacement);
            }
        }
        return new ConditionalRewrite(out.toString(), blockReason);
    }

    /** Builds "CASE expr WHEN s THEN r ... [ELSE d] END" from split DECODE arguments. */
    private String buildDecodeCase(List<String> args) {
        StringBuilder sb = new StringBuilder("CASE ").append(args.get(0).trim());
        int tail = args.size() - 1;
        int pairs;
        String defaultArg = null;
        if (tail % 2 == 1) {
            pairs = (tail - 1) / 2;
            defaultArg = args.get(args.size() - 1).trim();
        } else {
            pairs = tail / 2;
        }
        for (int k = 0; k < pairs; k++) {
            String search = args.get(1 + 2 * k).trim();
            String result = args.get(2 + 2 * k).trim();
            sb.append(" WHEN ").append(search).append(" THEN ").append(result);
        }
        if (defaultArg != null) {
            sb.append(" ELSE ").append(defaultArg);
        }
        return sb.append(" END").toString();
    }

    /**
     * Finds every code-level {@code DECODE(} / {@code NVL2(} call and its matching close
     * parenthesis. Literals, double-quoted identifiers and comments are skipped.
     */
    private List<ConditionalCall> findConditionalCalls(String sql,
                                                       DatabaseType.DialectFamily from) {
        List<ConditionalCall> calls = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        boolean inSingle = false;
        boolean inDouble = false;
        while (i < n) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                    i += 2;
                } else if (c == '\'') {
                    inSingle = false;
                    i++;
                } else {
                    i++;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"' && i + 1 < n && sql.charAt(i + 1) == '"') {
                    i += 2;
                } else if (c == '"') {
                    inDouble = false;
                    i++;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                i++;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                i++;
                continue;
            }
            int kind = -1;
            if (matchesWordAt(sql, i, "DECODE")) {
                kind = DECODE;
            } else if (matchesWordAt(sql, i, "NVL2")) {
                kind = NVL2;
            }
            if (kind >= 0) {
                int nameEnd = i + (kind == DECODE ? 6 : 4);
                int j = nameEnd;
                while (j < n && Character.isWhitespace(sql.charAt(j))) {
                    j++;
                }
                if (j < n && sql.charAt(j) == '(') {
                    int close = findCloseParen(sql, j, from);
                    if (close > 0) {
                        calls.add(new ConditionalCall(kind, i, j, close + 1));
                        i = close + 1;
                        continue;
                    }
                }
            }
            i++;
        }
        return calls;
    }

    private boolean matchesWordAt(String sql, int i, String word) {
        int n = sql.length();
        if (i + word.length() > n || !sql.regionMatches(true, i, word, 0, word.length())) {
            return false;
        }
        int after = i + word.length();
        return after == n || !Character.isLetterOrDigit(sql.charAt(after));
    }

    /**
     * Given an open parenthesis at {@code open}, returns the index of the matching close
     * parenthesis, or -1 if none. Quote (single/double), comment and nesting aware.
     */
    private int findCloseParen(String sql, int open, DatabaseType.DialectFamily from) {
        int depth = 0;
        int i = open;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    if (sql.charAt(i) == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '"' && i + 1 < n && sql.charAt(i + 1) == '"') {
                        i += 2;
                        continue;
                    }
                    if (sql.charAt(i) == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /**
     * Splits the inside of a function call at top-level commas, preserving nested calls,
     * literals (single and double quoted) and comments. Argument whitespace is kept.
     */
    private List<String> splitTopLevelArguments(String text,
                                                DatabaseType.DialectFamily from) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (text.charAt(i) == '\'' && i + 1 < n && text.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    if (text.charAt(i) == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n) {
                    if (text.charAt(i) == '"' && i + 1 < n && text.charAt(i + 1) == '"') {
                        i += 2;
                        continue;
                    }
                    if (text.charAt(i) == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(text.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        args.add(text.substring(start));
        return args;
    }

    private record TypeCall(String name, int start, int open, int closeExclusive) {
    }

    /**
     * Rewrites standards-shaped {@code CAST(expr AS type)} and the vendor {@code CONVERT(...)}
     * calls. The target type name is a DDL type for the source product and would otherwise be
     * rejected (or worse, accepted with a different meaning) on the target, so it goes through
     * the same {@link RoutineTypeMapper} machinery as routine signatures, then through the
     * target dialect's CAST allowlist.
     */
    CastRewrite rewriteCastAndConvert(String sql,
                                      DatabaseType sourceType,
                                      DatabaseType targetType) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (routineTypeMapper == null || dialectFactory == null
                || sourceType == null || targetType == null
                || sourceType.getFamily() == targetType.getFamily()) {
            return new CastRewrite(sql, new ArrayList<>());
        }
        DatabaseType.DialectFamily from = sourceType.getFamily();
        List<TypeCall> calls = findTypeCalls(sql);
        if (calls.isEmpty()) {
            return new CastRewrite(sql, new ArrayList<>());
        }
        SqlDialect targetDialect = dialectFactory.forType(targetType);
        StringBuilder out = new StringBuilder(sql);
        for (int idx = calls.size() - 1; idx >= 0; idx--) {
            TypeCall call = calls.get(idx);
            String inner = sql.substring(call.open() + 1, call.closeExclusive() - 1);
            String replacement = null;
            if ("CAST".equals(call.name())) {
                int as = topLevelKeywordIndex(inner, "AS");
                if (as > 0) {
                    String expr = inner.substring(0, as).trim();
                    String typeText = inner.substring(as + 2).trim();
                    String mapped = mapCastTypeName(typeText, sourceType, targetDialect);
                    if (mapped != null) {
                        replacement = "CAST(" + expr + " AS " + mapped + ")";
                    } else {
                        reasons.add("the body uses CAST with target type " + typeText
                                + ", which cannot be mapped to a type the target accepts inside"
                                + " CAST; rewrite the cast or provide a DDL override");
                    }
                }
            } else { // CONVERT
                List<String> args = splitTopLevelArguments(inner, from);
                switch (from) {
                    case ORACLE, POSTGRES ->
                        // Oracle: CONVERT(expr, dest_charset[, src_charset]); PostgreSQL:
                        // CONVERT(expr USING encoding). Both are character-set conversions.
                        reasons.add("the body uses CONVERT() for character set conversion, which"
                                + " has no equivalent expression on the target in this form;"
                                + " rewrite it or provide a DDL override");
                    case SQLSERVER -> {
                        if (args.size() == 2) {
                            String mapped = mapCastTypeName(args.get(0).trim(),
                                    sourceType, targetDialect);
                            if (mapped != null) {
                                replacement = "CAST(" + args.get(1).trim()
                                        + " AS " + mapped + ")";
                            } else {
                                reasons.add(unknownCastTypeReason(args.get(0).trim()));
                            }
                        } else if (args.size() >= 3) {
                            reasons.add("the body uses CONVERT() with a style argument, which"
                                    + " carries a vendor date/number format that standard CAST"
                                    + " cannot express; rewrite it with the target's formatting"
                                    + " function or provide a DDL override");
                        }
                    }
                    case MYSQL -> {
                        // CONVERT(expr USING charset) has no separating comma, so the USING
                        // keyword is detected at top level before argument splitting.
                        if (topLevelKeywordIndex(inner, "USING") >= 0
                                || (args.size() == 2
                                && args.get(1).trim().matches("(?is)^USING\\b.*"))) {
                            reasons.add("the body uses CONVERT(expr USING charset) for character"
                                    + " set conversion, which has no equivalent expression on"
                                    + " the target in this form; rewrite it or provide a DDL"
                                    + " override");
                        } else if (args.size() == 2) {
                            String mapped = mapCastTypeName(args.get(1).trim(),
                                    sourceType, targetDialect);
                            if (mapped != null) {
                                replacement = "CAST(" + args.get(0).trim()
                                        + " AS " + mapped + ")";
                            } else {
                                reasons.add(unknownCastTypeReason(args.get(1).trim()));
                            }
                        }
                    }
                    default -> {
                        // Other families have no well-defined CONVERT cast shape; leave it for
                        // the target to reject loudly.
                    }
                }
            }
            if (replacement != null) {
                out.replace(call.start(), call.closeExclusive(), replacement);
            }
        }
        return new CastRewrite(out.toString(), new ArrayList<>(reasons));
    }

    private static String unknownCastTypeReason(String typeText) {
        return "the body casts to type " + typeText + ", which cannot be mapped to a type the"
                + " target accepts inside CAST; rewrite the cast or provide a DDL override";
    }

    /**
     * Translates the source type declaration inside a CAST: first the MySQL CAST aliases
     * (SIGNED/INTEGER are 64-bit there), then the routine/column type machinery, then the
     * target dialect's CAST allowlist. Null means the target has no usable cast type.
     */
    private String mapCastTypeName(String typeText, DatabaseType sourceType,
                                   SqlDialect targetDialect) {
        String declaration = sourceType.getFamily() == DatabaseType.DialectFamily.MYSQL
                ? normalizeMysqlCastTarget(typeText) : typeText;
        RoutineTypeMapper.Mapping mapping =
                routineTypeMapper.map(declaration, null, sourceType, targetDialect);
        if (!mapping.resolved()) {
            return null;
        }
        return targetDialect.castType(mapping.typeName());
    }

    /** MySQL's CAST spells SIGNED [INTEGER], UNSIGNED [INTEGER] and INTEGER (a SIGNED alias). */
    private String normalizeMysqlCastTarget(String typeText) {
        String t = typeText.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        if (t.equals("SIGNED") || t.equals("SIGNED INTEGER") || t.equals("INTEGER")) {
            return "BIGINT";
        }
        if (t.equals("UNSIGNED") || t.equals("UNSIGNED INTEGER")) {
            return "BIGINT UNSIGNED";
        }
        return typeText;
    }

    /** Finds code-level {@code CAST(} and {@code CONVERT(} calls with matching close parens. */
    private List<TypeCall> findTypeCalls(String sql) {
        List<TypeCall> calls = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        boolean inSingle = false;
        boolean inDouble = false;
        while (i < n) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                    i += 2;
                } else if (c == '\'') {
                    inSingle = false;
                    i++;
                } else {
                    i++;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"' && i + 1 < n && sql.charAt(i + 1) == '"') {
                    i += 2;
                } else if (c == '"') {
                    inDouble = false;
                    i++;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                i++;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                i++;
                continue;
            }
            String name = null;
            if (matchesWordAt(sql, i, "CAST")) {
                name = "CAST";
            } else if (matchesWordAt(sql, i, "CONVERT")) {
                name = "CONVERT";
            }
            if (name != null) {
                int j = i + name.length();
                while (j < n && Character.isWhitespace(sql.charAt(j))) {
                    j++;
                }
                if (j < n && sql.charAt(j) == '(') {
                    int close = findCloseParen(sql, j, DatabaseType.DialectFamily.GENERIC);
                    if (close > 0) {
                        calls.add(new TypeCall(name, i, j, close + 1));
                        i = close + 1;
                        continue;
                    }
                }
            }
            i++;
        }
        return calls;
    }

    /**
     * Index of the first top-level {@code keyword} (e.g. AS) in call arguments: outside
     * quotes/comments and not nested in parentheses. -1 if absent or if the keyword appears
     * more than once at top level (a malformed/ambiguous CAST).
     */
    private int topLevelKeywordIndex(String text, String keyword) {
        int depth = 0;
        int found = -1;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < n) {
                    if (text.charAt(i) == quote && i + 1 < n && text.charAt(i + 1) == quote) {
                        i += 2;
                        continue;
                    }
                    if (text.charAt(i) == quote) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && matchesWordAt(text, i, keyword)) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
                i += keyword.length();
                continue;
            }
            i++;
        }
        return found;
    }

    private static final Pattern DATE_ARITHMETIC = Pattern.compile(
            "(?i)\\b(?:SYSDATE|SYSTIMESTAMP|NOW|CURRENT_TIMESTAMP|CURRENT_DATE|GETDATE|SYSDATETIME)"
                    + "\\b(?:\\s*\\([^()]*\\))?\\s*[+-]\\s*\\d+|\\b\\d+\\s*-\\s*"
                    + "(?:SYSDATE|SYSTIMESTAMP|NOW|CURRENT_TIMESTAMP|GETDATE|SYSDATETIME)\\b");

    private static final Pattern ROWNUM_ANY = Pattern.compile(
            "(?i)\\bROWNUM\\b");

    private static final Pattern TOP_PERCENT = Pattern.compile(
            "(?i)\\bTOP\\s+(?:\\d+|\\(\\s*\\d+\\s*\\))\\s+PERCENT\\b");

    private static final Pattern WITH_TIES = Pattern.compile(
            "(?i)\\bWITH\\s+TIES\\b");

    private static final Pattern OLD_OUTER_JOIN = Pattern.compile(
            "\\(\\s*\\+\\s*\\)");

    private static final Pattern PROCEDURAL_MARKER = Pattern.compile(
            "(?i)\\b(?:BEGIN|DECLARE|CURSOR|WHILE|REPEAT|LOOP|EXCEPTION|HANDLER|GOTO)\\b"
                    + "|\\bLANGUAGE\\s+PLPGSQL\\b|\\bEXECUTE\\s+IMMEDIATE\\b"
                    + "|\\bSP_EXECUTESQL\\b|%(?:TYPE|ROWTYPE)\\b|@\\w+");

    /**
     * Whether {@code sql} contains a backslash inside a single-quoted MySQL string literal.
     *
     * <p>Comments are skipped and doubled single quotes are modelled, mirroring the literal
     * rules of {@link #scan}; backtick and double-quoted identifiers are not strings and do not
     * count. Only called for MySQL sources, where backslash is an escape character.
     */
    boolean containsBackslashInMysqlLiteral(String sql) {
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\\') {
                        return true;
                    }
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            i++;
        }
        return false;
    }

    private static final Pattern EXCEPT_ALL = Pattern.compile(
            "(?i)\\bEXCEPT\\s+ALL\\b");

    /**
     * Rewrites MySQL's legacy {@code GROUP BY cols WITH ROLLUP} into the standard
     * {@code GROUP BY ROLLUP(cols)}. CUBE and GROUPING SETS cannot go to MySQL at all:
     * convert() reports them as blocks.
     */
    String convertGroupingExtensions(String sql, DatabaseType.DialectFamily from,
                                     DatabaseType.DialectFamily to) {
        if (from != DatabaseType.DialectFamily.MYSQL
                || to == DatabaseType.DialectFamily.MYSQL) {
            return sql;
        }
        // The capture is tempered against a second GROUP BY so an outer query cannot swallow
        // a nested subquery's WITH ROLLUP. Multi-line, since formatted bodies wrap.
        Pattern withRollup = Pattern.compile(
                "(?i)\\bGROUP\\s+BY\\s+((?:(?!\\bGROUP\\s+BY\\b)[\\s\\S])+?)\\s+WITH\\s+ROLLUP\\b");
        return applyOutsideLiterals(sql, from, to, code -> {
            Matcher m = withRollup.matcher(code);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        "GROUP BY ROLLUP(" + m.group(1).trim() + ")"));
            }
            m.appendTail(sb);
            return sb.toString();
        });
    }

    /**
     * Rewrites the set-difference keyword: Oracle spells it {@code MINUS}, the SQL:2003
     * families {@code EXCEPT}; HANA accepts both. Literal/comment-aware.
     */
    String convertSetDifference(String sql, DatabaseType.DialectFamily from,
                                 DatabaseType.DialectFamily to) {
        if (from == to) {
            return sql;
        }
        if (to == DatabaseType.DialectFamily.ORACLE) {
            // EXCEPT -> MINUS, but not EXCEPT ALL: convert() reports that as a block.
            return applyOutsideLiterals(sql, from, to, code -> code.replaceAll(
                    "(?i)\\bEXCEPT\\b(?!\\s+ALL\\b)", "MINUS"));
        }
        if (to != DatabaseType.DialectFamily.HANA
                && from == DatabaseType.DialectFamily.ORACLE) {
            return applyOutsideLiterals(sql, from, to, code -> code.replaceAll(
                    "(?i)\\bMINUS\\b", "EXCEPT"));
        }
        return sql;
    }

    /** Any code-level LIMIT (subqueries included); checked against masked text. */
    private static final Pattern LIMIT_ANY = Pattern.compile(
            "(?i)\\bLIMIT\\s+\\d+");

    /** A code-level FETCH ... ROWS ONLY not preceded by an OFFSET clause. */
    private static final Pattern FETCH_ANY = Pattern.compile(
            "(?i)\\bFETCH\\s+(?:FIRST|NEXT)\\s+\\d+\\s+ROWS?\\s+ONLY");

    /** CUBE grouping, either function form CUBE(...) or the old WITH CUBE spelling. */
    private static final Pattern CUBE_ANY = Pattern.compile(
            "(?i)\\bCUBE\\s*\\(|\\bWITH\\s+CUBE\\b");

    /** GROUPING SETS ((...), ...): not offered by MySQL in any release. */
    private static final Pattern GROUPING_SETS_ANY = Pattern.compile(
            "(?i)\\bGROUPING\\s+SETS\\b");

    /**
     * Functions whose <em>name</em> has an equivalent in the target but whose format string
     * does not.
     *
     * <p>These are deliberately left alone. Renaming {@code TO_CHAR(d,'YYYY-MM-DD')} to
     * {@code DATE_FORMAT(d,'YYYY-MM-DD')} produces valid SQL that returns the literal text
     * {@code YYYY-MM-DD}, because MySQL spells those fields {@code %Y-%m-%d} — a wrong answer
     * with no error, which is strictly worse than a failure that names the object. The same
     * name also serves unrelated purposes ({@code TO_CHAR} of a number is not date
     * formatting at all), so no mechanical rule is safe.
     */
    void warnOnFormatSensitiveFunctions(String sql, DatabaseType.DialectFamily from,
                                        DatabaseType.DialectFamily to) {
        java.util.regex.Matcher m = FORMAT_SENSITIVE.matcher(sql);
        Set<String> found = new LinkedHashSet<>();
        while (m.find()) {
            found.add(m.group(1).toUpperCase());
        }
        if (!found.isEmpty()) {
            log.warn("SQL body uses formatting function(s) {} whose format-string syntax "
                    + "differs between {} and {}. The names are left unchanged on purpose: a "
                    + "rename without translating the format string would return wrong values "
                    + "silently. Review this object and supply a manual DDL override if the "
                    + "target rejects it or the output differs.", found, from, to);
        }
    }

    /**
     * Rewrites identifier quoting. Only the delimiter is changed; the identifier inside is
     * left alone so case-sensitive names survive.
     */
    String convertIdentifierQuotes(String sql, DatabaseType.DialectFamily from,
                                   DatabaseType.DialectFamily to) {
        String result = sql;
        if (from == DatabaseType.DialectFamily.MYSQL) {
            // Backtick-quoted identifiers -> target quoting.
            result = replaceQuoted(result, '`', '`', to);
        } else if (from == DatabaseType.DialectFamily.SQLSERVER) {
            result = replaceBracketQuoted(result, to);
        } else if (to == DatabaseType.DialectFamily.MYSQL) {
            // Double-quoted identifiers would be read as string literals by MySQL unless
            // ANSI_QUOTES is set, so convert them to backticks.
            result = replaceQuoted(result, '"', '"', to);
        }
        return result;
    }

    private String replaceQuoted(String sql, char open, char close,
                                 DatabaseType.DialectFamily to) {
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inside = false;
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inside) {
                inString = !inString;
                sb.append(c);
                continue;
            }
            if (inString) {
                sb.append(c);
                continue;
            }
            if (!inside && c == open) {
                inside = true;
                sb.append(openQuoteFor(to));
            } else if (inside && c == close) {
                inside = false;
                sb.append(closeQuoteFor(to));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String replaceBracketQuoted(String sql, DatabaseType.DialectFamily to) {
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
                sb.append(c);
            } else if (!inString && c == '[') {
                sb.append(openQuoteFor(to));
            } else if (!inString && c == ']') {
                sb.append(closeQuoteFor(to));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private char openQuoteFor(DatabaseType.DialectFamily family) {
        switch (family) {
            case MYSQL:
                return '`';
            case SQLSERVER:
                return '[';
            default:
                return '"';
        }
    }

    private char closeQuoteFor(DatabaseType.DialectFamily family) {
        switch (family) {
            case MYSQL:
                return '`';
            case SQLSERVER:
                return ']';
            default:
                return '"';
        }
    }

    /**
     * MySQL uses {@code CONCAT(a, b)} while the others use {@code a || b}. Converting
     * {@code ||} into a CONCAT call requires parsing operand boundaries, so only the safe
     * direction (CONCAT is understood almost everywhere) is applied.
     */
    String convertConcatenation(String sql, DatabaseType.DialectFamily from,
                                DatabaseType.DialectFamily to) {
        if (to == DatabaseType.DialectFamily.MYSQL && from != DatabaseType.DialectFamily.MYSQL) {
            // MySQL treats || as logical OR unless PIPES_AS_CONCAT is set, which would
            // silently produce wrong results. Flag it rather than mistranslate.
            if (containsStringConcatenation(sql)) {
                log.warn("SQL body uses '||' string concatenation, which MySQL reads as "
                        + "logical OR. Review the converted object and supply a manual "
                        + "override if the semantics differ.");
            }
        }
        return sql;
    }

    /**
     * Whether the body contains an unquoted {@code ||}, i.e. string concatenation in the
     * Oracle/PostgreSQL/SQL Server sense rather than text in a literal or a comment.
     *
     * <p>Used both for the conversion log and for the project page's pre-run warning when
     * the target is in the MySQL family: those products read the same token as logical OR,
     * so the object is created but returns 0/1 — a wrong answer instead of an error.
     *
     * <p>Single-quoted strings (including a doubled quote), double-quoted identifiers and
     * literals, {@code --} line comments and {@code /* *}{@code /} block comments are
     * skipped. Product-specific quoting such as Oracle's {@code q'[...]'} or PostgreSQL's
     * dollar quotes is not modelled; a pipe inside one of those can only produce a warning,
     * never a rewrite, so the conservative miss is acceptable.
     */
    public static boolean containsStringConcatenation(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inSingle) {
                if (c == '\'' && next == '\'') {
                    i++; // a doubled quote stays inside the literal
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"' && next == '"') {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '|' && next == '|') {
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrites MySQL's one-argument predicate {@code ISNULL(x)} into the standard
     * {@code x IS NULL} before the function-name maps can see it.
     *
     * <p>MySQL overloads the name: {@code ISNULL(x)} is a predicate returning 1/0, whereas SQL
     * Server's {@code ISNULL(a, b)} is a two-argument null-coalescing call. The coalesce maps
     * were written for the latter, so applying them to the MySQL form produced
     * {@code COALESCE(x)} on PostgreSQL — legal, but NULL when x is NULL, the opposite truth
     * value. Only the MySQL family uses the predicate spelling, so this runs for MySQL sources
     * only, before {@link #convertFunctions}.
     */
    String convertMysqlIsNullPredicate(String sql, DatabaseType.DialectFamily from,
                                       DatabaseType.DialectFamily to) {
        if (from != DatabaseType.DialectFamily.MYSQL || to == DatabaseType.DialectFamily.MYSQL) {
            return sql;
        }
        return applyOutsideLiterals(sql, from, to, this::rewriteIsNullCalls);
    }

    private static final Pattern ISNULL_CALL = Pattern.compile(
            "(?i)\\bISNULL\\s*\\(");

    /** Balanced-parenthesis rewrite of every ISNULL(...) call in a literal-free code segment. */
    private String rewriteIsNullCalls(String code) {
        Matcher m = ISNULL_CALL.matcher(code);
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            int open = code.indexOf('(', m.start());
            int depth = 1;
            int j = open + 1;
            while (j < code.length() && depth > 0) {
                char c = code.charAt(j);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                j++;
            }
            if (depth > 0) {
                break; // unbalanced: leave the remainder alone rather than emit broken SQL
            }
            String argument = code.substring(open + 1, j - 1).trim();
            sb.append(code, pos, m.start());
            sb.append(argument).append(" IS NULL");
            pos = j;
        }
        sb.append(code, pos, code.length());
        return sb.toString();
    }

    /**
     * Values that are complete without a call: bare SQL keywords. A source {@code NOW()}
     * translated to one of these must drop its parentheses — {@code SYSDATE()} is invalid —
     * where a plain function-name value like {@code IFNULL} must keep them.
     */
    private static final Set<String> PARENLESS_VALUES =
            Set.of("SYSDATE", "CURRENT_TIMESTAMP", "CURRENT DATE", "CURRENT_DATE",
                    "CURRENT TIMESTAMP");

    /** Empty RAND() → RANDOM() on PostgreSQL-family targets; seeded RAND(seed) is left alone. */
    private static final Pattern EMPTY_RAND = Pattern.compile(
            "(?i)\\bRAND\\s*\\(\\s*\\)");

    /**
     * Applies whole-word function name substitutions for the target family.
     *
     * <p>Products disagree on whether their date/time entry points are keywords or functions:
     * Oracle's {@code SYSDATE} takes no parentheses, MySQL's {@code NOW()} requires them. The
     * matcher therefore also consumes an <em>empty</em> call-parens group and re-emits it only
     * when the replacement needs it: a value ending in {@code )} is already a complete call
     * ({@code NOW()}, {@code TRUNC(SYSDATE)}), a value in {@link #PARENLESS_VALUES} forbids
     * parens, and any other value is a plain function name that keeps the source's parens —
     * which also covers argument lists, since {@code NVL(a, b)} never matches the empty-parens
     * group and is renamed in place.
     */
    String convertFunctions(String sql, DatabaseType.DialectFamily from,
                            DatabaseType.DialectFamily to) {
        Map<String, String> map = functionMaps.get(to);
        if (map == null) {
            return sql;
        }
        // Only real code is rewritten: a literal or a quoted identifier that happens to spell
        // a function name is left alone.
        return applyOutsideLiterals(sql, from, to, code -> {
            // NOW(fsp) needs a precision-aware replacement before the plain name rule, which
            // would otherwise leave a dangling precision (SYSDATE(6), GETDATE()(6)).
            String result = rewriteNowPrecision(code, to);
            // Only the empty RAND() call maps to PostgreSQL's RANDOM(); a seeded RAND(seed)
            // would become RANDOM(seed), which PostgreSQL rejects, so leave it for loud failure.
            if (to == DatabaseType.DialectFamily.POSTGRES) {
                result = result.replaceAll(
                        "(?i)\\bRAND\\s*\\(\\s*\\)", "RANDOM()");
            }
            for (Map.Entry<String, String> e : map.entrySet()) {
                String value = e.getValue();
                boolean valueIsCompleteCall = value.endsWith(")");
                boolean valueForbidsParens = PARENLESS_VALUES.contains(value);
                // Word boundary on both sides so SUBSTR does not match SUBSTRING; the
                // optional group captures an empty call so NOW() can become SYSDATE.
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                        "(?i)\\b" + java.util.regex.Pattern.quote(e.getKey())
                                + "\\b(\\s*\\(\\s*\\))?");
                java.util.regex.Matcher m = p.matcher(result);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    boolean hadEmptyParens = m.group(1) != null;
                    boolean keepParens = hadEmptyParens
                            && !valueIsCompleteCall && !valueForbidsParens;
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                            value + (keepParens ? "()" : "")));
                }
                m.appendTail(sb);
                result = sb.toString();
            }
            return result;
        });
    }

    /** {@code NOW(3)} and friends — the precision argument must not survive a plain rename. */
    private static final Pattern NOW_WITH_PRECISION = Pattern.compile(
            "(?i)\\bNOW\\s*\\(\\s*(\\d+)\\s*\\)");

    /**
     * Replaces precision-qualified {@code NOW(fsp)} calls for the two families whose entry
     * point cannot take that argument.
     *
     * <p>Oracle's {@code SYSTIMESTAMP} (unlike {@code SYSDATE}) keeps fractional seconds, and
     * SQL Server's {@code SYSDATETIME()} carries more precision than {@code GETDATE()} (which
     * is rounded to 1/300 s). MySQL and PostgreSQL use {@code NOW(fsp)} natively, and a later
     * DB2 name rename produces {@code CURRENT TIMESTAMP(fsp)}, which DB2 accepts — so those
     * families need nothing here.
     */
    private String rewriteNowPrecision(String code, DatabaseType.DialectFamily to) {
        if (to != DatabaseType.DialectFamily.ORACLE
                && to != DatabaseType.DialectFamily.SQLSERVER) {
            return code;
        }
        java.util.regex.Matcher m = NOW_WITH_PRECISION.matcher(code);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = to == DatabaseType.DialectFamily.ORACLE
                    ? "SYSTIMESTAMP" : "SYSDATETIME()";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Applies {@code transform} to the executable parts of {@code sql}, leaving string
     * literals, quoted identifiers and comments untouched.
     *
     * <p>A blind {@code replaceAll} over the whole body rewrites text it has no business
     * touching: {@code SELECT 'NVL means null value logic'} became
     * {@code SELECT 'IFNULL means null value logic'}, and a column named {@code "LEN"} was
     * renamed to {@code "LENGTH"}.
     *
     * <p>Every character of the input is emitted exactly once — through {@code transform} or
     * verbatim — so a region this misjudges can only cost a missed substitution, never
     * corrupted output.
     */
    String applyOutsideLiterals(String sql, DatabaseType.DialectFamily from,
                                DatabaseType.DialectFamily to,
                                UnaryOperator<String> transform) {
        return scan(sql, from, to, transform, false);
    }

    /**
     * Returns {@code sql} with every literal, quoted identifier and comment replaced by
     * spaces (newlines preserved); executable code is kept verbatim.
     *
     * <p>Used to test whole-body structural questions ("is there a FROM anywhere?") without a
     * blind regex matching words hidden inside literals or comments. Keeping positions and line
     * breaks lets anchors like {@code $} keep working against the masked text.
     */
    String maskNonCode(String sql, DatabaseType.DialectFamily from,
                       DatabaseType.DialectFamily to) {
        return scan(sql, from, to, null, true);
    }

    private String scan(String sql, DatabaseType.DialectFamily from,
                        DatabaseType.DialectFamily to,
                        UnaryOperator<String> transform, boolean maskQuoted) {
        // Quoting is checked against both families: this runs after convertIdentifierQuotes,
        // so a body may already carry the target's delimiters. Over-protecting only skips a
        // substitution; under-protecting corrupts a literal.
        boolean backtick = from == DatabaseType.DialectFamily.MYSQL
                || to == DatabaseType.DialectFamily.MYSQL;
        boolean bracket = from == DatabaseType.DialectFamily.SQLSERVER
                || to == DatabaseType.DialectFamily.SQLSERVER;
        // Backslash escapes are a MySQL extension. Assuming them elsewhere would mis-read an
        // Oracle literal that legitimately ends in a backslash.
        boolean backslashEscapes = from == DatabaseType.DialectFamily.MYSQL;

        StringBuilder out = new StringBuilder(sql.length());
        StringBuilder code = new StringBuilder();
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);

            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                flushCode(out, code, transform);
                int end = sql.indexOf('\n', i);
                end = end < 0 ? n : end + 1;
                appendSpan(out, sql, i, end, maskQuoted);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                flushCode(out, code, transform);
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                appendSpan(out, sql, i, end, maskQuoted);
                i = end;
                continue;
            }

            boolean opensQuote = c == '\'' || c == '"'
                    || (backtick && c == '`')
                    || (bracket && c == '[');
            if (opensQuote) {
                flushCode(out, code, transform);
                char close = c == '[' ? ']' : c;
                int j = i + 1;
                while (j < n) {
                    char d = sql.charAt(j);
                    if (backslashEscapes && c == '\'' && d == '\\' && j + 1 < n) {
                        j += 2;
                        continue;
                    }
                    if (d == close) {
                        // A doubled delimiter escapes itself; brackets have no such form.
                        if (close != ']' && j + 1 < n && sql.charAt(j + 1) == close) {
                            j += 2;
                            continue;
                        }
                        j++;
                        break;
                    }
                    j++;
                }
                int end = Math.min(j, n);
                appendSpan(out, sql, i, end, maskQuoted);
                i = end;
                continue;
            }

            code.append(c);
            i++;
        }
        flushCode(out, code, transform);
        return out.toString();
    }

    /** Emits a non-code span verbatim, or — when masking — as spaces with line breaks kept. */
    private void appendSpan(StringBuilder out, String sql, int start, int end,
                            boolean maskQuoted) {
        if (!maskQuoted) {
            out.append(sql, start, end);
            return;
        }
        for (int k = start; k < end; k++) {
            char c = sql.charAt(k);
            out.append(c == '\n' || c == '\r' ? c : ' ');
        }
    }

    private void flushCode(StringBuilder out, StringBuilder code,
                           UnaryOperator<String> transform) {
        if (code.length() > 0) {
            out.append(transform == null ? code : transform.apply(code.toString()));
            code.setLength(0);
        }
    }

    /** Rewrites row-limiting syntax between LIMIT, ROWNUM, TOP and FETCH FIRST. */
    /** A LIMIT/FETCH row-limiting clause ends here: end of text, a semicolon or a subquery. */
    private static final String PAGE_END = "(?=\\s*(?:;|\\)|$))";

    String convertLimitClause(String sql, DatabaseType.DialectFamily from,
                              DatabaseType.DialectFamily to) {
        if (to == DatabaseType.DialectFamily.ORACLE || to == DatabaseType.DialectFamily.DB2) {
            // LIMIT n -> FETCH FIRST n ROWS ONLY (Oracle 12c+, DB2). Literal-aware: a view
            // body containing "LIMIT 5" inside a string must not be rewritten. The clause may
            // end a derived-table subquery or sit before a semicolon, not just the whole text,
            // and the offset forms ("LIMIT n OFFSET m", "LIMIT m, n") must be handled before
            // the bare one; left alone they reach Oracle/DB2 verbatim and fail on every cycle.
            return applyOutsideLiterals(sql, from, to, code -> code
                    .replaceAll("(?i)\\bLIMIT\\s+(\\d+)\\s+OFFSET\\s+(\\d+)" + PAGE_END,
                            "OFFSET $2 ROWS FETCH FIRST $1 ROWS ONLY")
                    .replaceAll("(?i)\\bLIMIT\\s+(\\d+)\\s*,\\s*(\\d+)" + PAGE_END,
                            "OFFSET $1 ROWS FETCH FIRST $2 ROWS ONLY")
                    .replaceAll("(?i)\\bLIMIT\\s+(\\d+)" + PAGE_END,
                            "FETCH FIRST $1 ROWS ONLY"));
        }
        if (to == DatabaseType.DialectFamily.MYSQL) {
            // Reverse direction: FETCH FIRST/OFFSET-FETCH (Oracle, DB2, PostgreSQL) and
            // T-SQL's FETCH NEXT are the same semantics as MySQL LIMIT. The OFFSET form is
            // replaced before the bare one so the latter cannot eat its FETCH tail.
            return applyOutsideLiterals(sql, from, to, code -> code
                    .replaceAll("(?i)\\bOFFSET\\s+(\\d+)\\s+ROWS?\\s+FETCH\\s+"
                            + "(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+ONLY" + PAGE_END,
                            "LIMIT $2 OFFSET $1")
                    .replaceAll("(?i)\\bFETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+ONLY"
                            + PAGE_END, "LIMIT $1"));
        }
        if (to == DatabaseType.DialectFamily.SQLSERVER) {
            // LIMIT is left intact: convert() reports it as a blocking reason (stripping it
            // would silently widen the result set, and TOP conversion needs real parsing).
            return sql;
        }
        return sql;
    }

    /** Oracle's {@code FROM DUAL} has no equivalent elsewhere and must be dropped. */
    String convertDualTable(String sql, DatabaseType.DialectFamily from,
                            DatabaseType.DialectFamily to) {
        String result = sql;
        if (from == DatabaseType.DialectFamily.ORACLE && to != DatabaseType.DialectFamily.ORACLE) {
            result = applyOutsideLiterals(result, from, to, code -> {
                if (to == DatabaseType.DialectFamily.DB2) {
                    return code.replaceAll("(?i)\\bFROM\\s+DUAL\\b",
                            "FROM SYSIBM.SYSDUMMY1");
                }
                if (to == DatabaseType.DialectFamily.HANA) {
                    return code.replaceAll("(?i)\\bFROM\\s+DUAL\\b", "FROM DUMMY");
                }
                return code.replaceAll("(?i)\\s+FROM\\s+DUAL\\b", "");
            });
        } else if (from == DatabaseType.DialectFamily.HANA && to != DatabaseType.DialectFamily.HANA) {
            // DUMMY is HANA's dummy table. Oracle needs DUAL, MySQL accepts it, DB2 has its
            // own; PostgreSQL and SQL Server have none, so the clause goes.
            result = applyOutsideLiterals(result, from, to, code -> {
                if (to == DatabaseType.DialectFamily.ORACLE
                        || to == DatabaseType.DialectFamily.MYSQL) {
                    return code.replaceAll("(?i)\\bFROM\\s+DUMMY\\b", "FROM DUAL");
                }
                if (to == DatabaseType.DialectFamily.DB2) {
                    return code.replaceAll("(?i)\\bFROM\\s+DUMMY\\b", "FROM SYSIBM.SYSDUMMY1");
                }
                return code.replaceAll("(?i)\\s+FROM\\s+DUMMY\\b", "");
            });
        }
        if (to == DatabaseType.DialectFamily.ORACLE && from != DatabaseType.DialectFamily.ORACLE) {
            // A bare SELECT without FROM is invalid in Oracle. Test the masked text so a FROM
            // mentioned inside a literal or comment is not mistaken for a real FROM clause.
            String masked = maskNonCode(result, from, to);
            if (masked.matches("(?is)^\\s*SELECT\\s+(?!.*\\bFROM\\b).*$")) {
                result = result.trim() + " FROM DUAL";
            }
        } else if (to == DatabaseType.DialectFamily.HANA
                && from != DatabaseType.DialectFamily.HANA) {
            // Same for HANA, which rejects a SELECT without FROM and spells the dummy table
            // DUMMY. Testing the masked text keeps a literal that mentions FROM from
            // suppressing the fix.
            String masked = maskNonCode(result, from, to);
            if (masked.matches("(?is)^\\s*SELECT\\s+(?!.*\\bFROM\\b).*$")) {
                result = result.trim() + " FROM DUMMY";
            }
        }
        return result;
    }

    /**
     * Strips a {@code CREATE [OR REPLACE] VIEW x AS} prefix, leaving only the SELECT body.
     * Products differ in whether the stored definition includes the header.
     */
    public String extractViewBody(String definition) {
        if (definition == null) {
            return null;
        }
        String s = definition.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:ALGORITHM|DEFINER|SQL\\s+SECURITY)[^\\s]*\\s+)*VIEW\\s+.+?\\s+AS\\s+(.*)$")
                .matcher(s);
        if (m.matches()) {
            s = m.group(1).trim();
        }
        return s.endsWith(";") ? s.substring(0, s.length() - 1).trim() : s;
    }
}
