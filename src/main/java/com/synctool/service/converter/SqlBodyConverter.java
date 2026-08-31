package com.synctool.service.converter;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.synctool.model.DatabaseType;

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
 * override through {@link com.synctool.dto.SyncConfig#getDdlOverrides()}.
 */
@Component
@Slf4j
public class SqlBodyConverter {

    /** Function name translations, keyed by target family. */
    private final Map<DatabaseType.DialectFamily, Map<String, String>> functionMaps = new LinkedHashMap<>();

    public SqlBodyConverter() {
        buildFunctionMaps();
    }

    private void buildFunctionMaps() {
        Map<String, String> toMysql = new LinkedHashMap<>();
        toMysql.put("NVL", "IFNULL");
        toMysql.put("SYSDATE", "NOW()");
        toMysql.put("SYSTIMESTAMP", "NOW()");
        toMysql.put("GETDATE", "NOW");
        toMysql.put("SUBSTR", "SUBSTRING");
        toMysql.put("TO_CHAR", "DATE_FORMAT");
        toMysql.put("LEN", "LENGTH");
        toMysql.put("ISNULL", "IFNULL");
        functionMaps.put(DatabaseType.DialectFamily.MYSQL, toMysql);

        Map<String, String> toOracle = new LinkedHashMap<>();
        toOracle.put("IFNULL", "NVL");
        toOracle.put("ISNULL", "NVL");
        toOracle.put("NOW", "SYSDATE");
        toOracle.put("GETDATE", "SYSDATE");
        toOracle.put("CURDATE", "TRUNC(SYSDATE)");
        toOracle.put("SUBSTRING", "SUBSTR");
        toOracle.put("LEN", "LENGTH");
        functionMaps.put(DatabaseType.DialectFamily.ORACLE, toOracle);

        Map<String, String> toPostgres = new LinkedHashMap<>();
        toPostgres.put("NVL", "COALESCE");
        toPostgres.put("IFNULL", "COALESCE");
        toPostgres.put("ISNULL", "COALESCE");
        toPostgres.put("SYSDATE", "CURRENT_TIMESTAMP");
        toPostgres.put("SYSTIMESTAMP", "CURRENT_TIMESTAMP");
        toPostgres.put("GETDATE", "NOW");
        toPostgres.put("LEN", "LENGTH");
        functionMaps.put(DatabaseType.DialectFamily.POSTGRES, toPostgres);

        Map<String, String> toSqlServer = new LinkedHashMap<>();
        toSqlServer.put("NVL", "ISNULL");
        toSqlServer.put("IFNULL", "ISNULL");
        toSqlServer.put("SYSDATE", "GETDATE()");
        toSqlServer.put("SYSTIMESTAMP", "SYSDATETIME()");
        toSqlServer.put("NOW", "GETDATE");
        toSqlServer.put("LENGTH", "LEN");
        toSqlServer.put("SUBSTR", "SUBSTRING");
        functionMaps.put(DatabaseType.DialectFamily.SQLSERVER, toSqlServer);

        Map<String, String> toDb2 = new LinkedHashMap<>();
        toDb2.put("NVL", "COALESCE");
        toDb2.put("IFNULL", "COALESCE");
        toDb2.put("ISNULL", "COALESCE");
        toDb2.put("SYSDATE", "CURRENT TIMESTAMP");
        toDb2.put("GETDATE", "CURRENT TIMESTAMP");
        toDb2.put("NOW", "CURRENT TIMESTAMP");
        functionMaps.put(DatabaseType.DialectFamily.DB2, toDb2);
    }

    /**
     * Rewrites a SQL body from the source product's dialect into the target's.
     *
     * @return the converted SQL, or the input unchanged when source and target are the same
     *         family
     */
    public String convert(String sql, DatabaseType sourceType, DatabaseType targetType) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        DatabaseType.DialectFamily from = sourceType == null
                ? DatabaseType.DialectFamily.GENERIC : sourceType.getFamily();
        DatabaseType.DialectFamily to = targetType == null
                ? DatabaseType.DialectFamily.GENERIC : targetType.getFamily();
        if (from == to) {
            return sql;
        }

        String result = sql;
        result = convertIdentifierQuotes(result, from, to);
        result = convertConcatenation(result, from, to);
        result = convertFunctions(result, to);
        result = convertLimitClause(result, from, to);
        result = convertDualTable(result, from, to);
        return result;
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
            if (sql.contains("||")) {
                log.warn("SQL body uses '||' string concatenation, which MySQL reads as "
                        + "logical OR. Review the converted object and supply a manual "
                        + "override if the semantics differ.");
            }
        }
        return sql;
    }

    /** Applies whole-word function name substitutions for the target family. */
    String convertFunctions(String sql, DatabaseType.DialectFamily to) {
        Map<String, String> map = functionMaps.get(to);
        if (map == null) {
            return sql;
        }
        String result = sql;
        for (Map.Entry<String, String> e : map.entrySet()) {
            // Word boundary on both sides so SUBSTR does not match SUBSTRING.
            result = result.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b",
                    java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return result;
    }

    /** Rewrites row-limiting syntax between LIMIT, ROWNUM, TOP and FETCH FIRST. */
    String convertLimitClause(String sql, DatabaseType.DialectFamily from,
                              DatabaseType.DialectFamily to) {
        String result = sql;
        if (to == DatabaseType.DialectFamily.ORACLE) {
            // LIMIT n -> FETCH FIRST n ROWS ONLY (12c+).
            result = result.replaceAll("(?i)\\bLIMIT\\s+(\\d+)\\s*$", "FETCH FIRST $1 ROWS ONLY");
        } else if (to == DatabaseType.DialectFamily.SQLSERVER) {
            result = result.replaceAll("(?i)\\bLIMIT\\s+(\\d+)\\s*$", "");
            // A TOP clause must go next to SELECT, which needs real parsing; warn instead.
            if (!result.equals(sql)) {
                log.warn("Removed a LIMIT clause while converting to SQL Server; add TOP or "
                        + "OFFSET/FETCH manually if row limiting was intended.");
            }
        } else if (to == DatabaseType.DialectFamily.DB2) {
            result = result.replaceAll("(?i)\\bLIMIT\\s+(\\d+)\\s*$", "FETCH FIRST $1 ROWS ONLY");
        }
        return result;
    }

    /** Oracle's {@code FROM DUAL} has no equivalent elsewhere and must be dropped. */
    String convertDualTable(String sql, DatabaseType.DialectFamily from,
                            DatabaseType.DialectFamily to) {
        if (from == DatabaseType.DialectFamily.ORACLE && to != DatabaseType.DialectFamily.ORACLE) {
            if (to == DatabaseType.DialectFamily.DB2) {
                return sql.replaceAll("(?i)\\bFROM\\s+DUAL\\b", "FROM SYSIBM.SYSDUMMY1");
            }
            return sql.replaceAll("(?i)\\s+FROM\\s+DUAL\\b", "");
        }
        if (to == DatabaseType.DialectFamily.ORACLE && from != DatabaseType.DialectFamily.ORACLE) {
            // A bare SELECT without FROM is invalid in Oracle.
            if (sql.matches("(?is)^\\s*SELECT\\s+(?!.*\\bFROM\\b).*$")) {
                return sql.trim() + " FROM DUAL";
            }
        }
        return sql;
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
