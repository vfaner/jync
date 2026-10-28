package com.qqmu.jync.service.cdc;

import java.sql.Types;

/**
 * Converts a LogMiner SQL_REDO literal fragment into the Java value JdbcRowMapper.bind
 * expects. Pure function, no IO — every branch has a matrix test in
 * OracleLiteralConvertersTest.
 *
 * <p>LogMiner emits ALL values as quoted strings in the SQL_REDO text (even NUMBER
 * columns arrive as {@code '1'}), and date/timestamp values arrive wrapped in
 * {@code TO_DATE('...','...')} / {@code TO_TIMESTAMP('...FF...')} whose inner format is
 * NLS-dependent — the 23ai fixture recorded {@code TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')}.
 * We cannot regex-parse the NLS text; instead we surface the inner string verbatim and
 * let the target JDBC driver interpret it via {@code setObject(sqlType, string)} — the
 * same contract the baseline getString path uses for temporal columns.
 */
final class OracleLiteralConverters {

    private OracleLiteralConverters() { }

    static final class UnsupportedLiteralException extends RuntimeException {
        UnsupportedLiteralException(String message) {
            super(message);
        }
    }

    /**
     * Convert one literal fragment (already stripped of its outer SQL_REDO context) into
     * the canonical Java value for the declared target JDBC type.
     *
     * @param literal the raw literal as it appears in SQL_REDO, e.g. {@code 'hello'},
     *                {@code '1'}, {@code NULL}, {@code TO_TIMESTAMP('...')},
     *                {@code HEXTORAW('DEAD')}
     * @param sqlType the target column's {@link Types} value (from CdcTableBinding.typeByColumn)
     */
    static Object convert(String literal, int sqlType) {
        if (literal == null || literal.isEmpty()) {
            throw new UnsupportedLiteralException("empty literal");
        }
        String trimmed = literal.trim();

        if ("NULL".equalsIgnoreCase(trimmed)) {
            return null;
        }

        // N'...' national character string — strip the N prefix and parse as a quoted string.
        if (trimmed.startsWith("N'") && trimmed.endsWith("'")) {
            return unquote(trimmed.substring(1));
        }

        // P2-6: q'[...]' alternative quoting — content is verbatim between the
        // delimiters (no '' escaping), so it may hold quotes/commas/"and". Same
        // numeric-coercion contract as the plain quoted branch. An unterminated
        // q-quote is never guessed at: fall through to unsupported.
        if ((trimmed.startsWith("q'") || trimmed.startsWith("Q'"))
                && trimmed.length() >= 5) {
            String text = unquoteAlternative(trimmed);
            if (text != null) {
                if (isNumericType(sqlType)) {
                    return parseNumber(text);
                }
                return text;
            }
        }

        // Quoted string literal: '...' with '' escape.
        if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
            String text = unquote(trimmed);
            // For numeric/temporal target columns, LogMiner still ships the value as a
            // quoted string; coerce to the target type when the text is a clean number.
            if (isNumericType(sqlType)) {
                return parseNumber(text);
            }
            return text;
        }

        // Unquoted numeric literal (LogMiner sometimes emits bare numbers for NUMBER
        // columns when supplemental logging is minimal — rare, but accept it).
        if (isNumericType(sqlType) && looksLikeNumber(trimmed)) {
            return parseNumber(trimmed);
        }

        // ANSI temporal literal — DM's LogMiner renders DATE'…' / TIMESTAMP'…' (including
        // the time-zone form, whose offset sits inside the string). Oracle renders
        // TO_DATE/TO_TIMESTAMP instead, so this branch never fires on an Oracle source.
        // Same contract as the function-call branch below: the inner text is surfaced
        // verbatim and the target driver interprets it.
        String ansiTemporal = ansiTemporalContent(trimmed);
        if (ansiTemporal != null) {
            return ansiTemporal;
        }

        // DM renders binary columns as a 0x… hex literal; Oracle uses HEXTORAW('…').
        // An empty value arrives as a bare "0x" and decodes to an empty array.
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '0'
                && (trimmed.charAt(1) == 'x' || trimmed.charAt(1) == 'X')) {
            return hexToBytes(trimmed.substring(2));
        }

        // TO_DATE('...','...') — surface the inner date string verbatim; the target JDBC
        // driver interprets it via setObject(sqlType, string).
        if (trimmed.startsWith("TO_DATE(")) {
            return extractFirstStringArg(trimmed);
        }

        // TO_TIMESTAMP('...') or TO_TIMESTAMP('...','...') — same verbatim strategy.
        if (trimmed.startsWith("TO_TIMESTAMP(")) {
            return extractFirstStringArg(trimmed);
        }

        // TO_TIMESTAMP_TZ('...','...') — same.
        if (trimmed.startsWith("TO_TIMESTAMP_TZ(")) {
            return extractFirstStringArg(trimmed);
        }

        // HEXTORAW('DEAD') → byte[] for RAW target columns; the hex text is the canonical
        // form JdbcRowMapper.bind expects for VARBINARY/BINARY.
        if (trimmed.startsWith("HEXTORAW(") && trimmed.endsWith(")")) {
            String inner = trimmed.substring("HEXTORAW(".length(), trimmed.length() - 1).trim();
            if (inner.startsWith("'") && inner.endsWith("'")) {
                return hexToBytes(unquote(inner));
            }
            throw new UnsupportedLiteralException("HEXTORAW inner is not a quoted hex: " + trimmed);
        }

        // EMPTY_CLOB() / EMPTY_BLOB() — LogMiner emits these for empty LOBs.
        if ("EMPTY_CLOB()".equalsIgnoreCase(trimmed)) {
            return "";
        }
        if ("EMPTY_BLOB()".equalsIgnoreCase(trimmed)) {
            return new byte[0];
        }

        // ROWID = '...' appears in UPDATE/DELETE WHERE — the parser handles ROWID
        // recognition at the SQL level; if we ever see it here, reject.
        if (trimmed.startsWith("ROWID")) {
            throw new UnsupportedLiteralException("ROWID literal in value position: " + trimmed);
        }

        throw new UnsupportedLiteralException("unsupported literal: "
                + (trimmed.length() > 60 ? trimmed.substring(0, 60) + "…" : trimmed));
    }

    // —— helpers ——

    /**
     * Unwrap an ANSI temporal literal — {@code DATE'2026-03-04'} or
     * {@code TIMESTAMP'2026-03-04 05:06:07.123456[ +08:00]'} — returning the content
     * between the quotes, or null when {@code literal} is not one.
     *
     * <p>Measured on DM8 (see docs/dm-logminer-spike.md): DM always renders temporals this
     * way, with the time-zone offset inside the string. Oracle never does, so recognising
     * it cannot change an Oracle-parsed row.
     */
    private static String ansiTemporalContent(String literal) {
        int quote = literal.indexOf('\'');
        if (quote <= 0 || literal.length() <= quote + 1 || !literal.endsWith("'")) {
            return null;
        }
        String prefix = literal.substring(0, quote).toUpperCase(java.util.Locale.ROOT);
        if (!"DATE".equals(prefix) && !"TIMESTAMP".equals(prefix)) {
            return null;
        }
        return unquote(literal.substring(quote));
    }

    /** Strip the surrounding quotes and un-double the internal '' escape. */
    private static String unquote(String quoted) {
        // quoted starts and ends with ' (caller guarantees it).
        String inner = quoted.substring(1, quoted.length() - 1);
        return inner.replace("''", "'");
    }

    /**
     * P2-6: unwrap q'[...]' / q'{...}' / q'(...)' / q'<...>' (and exotic single-char
     * delimiter pairs) to their verbatim content. A close-bracket inside the content is
     * legal when not followed by a quote, so matching is on the terminator pair
     * {@code close + '}. Null when the terminator is absent — the caller then treats
     * the literal as unsupported rather than guessing.
     */
    private static String unquoteAlternative(String literal) {
        char open = literal.charAt(2);
        char close = switch (open) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> open;
        };
        if (!literal.endsWith(close + "'")) {
            return null;
        }
        return literal.substring(3, literal.length() - 2);
    }

    private static boolean isNumericType(int sqlType) {
        return sqlType == Types.BIGINT || sqlType == Types.INTEGER || sqlType == Types.SMALLINT
                || sqlType == Types.TINYINT || sqlType == Types.DECIMAL || sqlType == Types.NUMERIC
                || sqlType == Types.DOUBLE || sqlType == Types.FLOAT || sqlType == Types.REAL;
    }

    private static boolean looksLikeNumber(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int i = 0;
        if (s.charAt(0) == '+' || s.charAt(0) == '-') {
            i = 1;
        }
        boolean sawDigit = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                sawDigit = true;
            } else if (c != '.' && c != 'e' && c != 'E' && c != '+' && c != '-') {
                return false;
            }
        }
        return sawDigit;
    }

    private static Object parseNumber(String text) {
        // Keep it as a string for the target JDBC — JdbcRowMapper.bind handles numeric
        // coercion via setObject(sqlType, string). This mirrors the binlog path's
        // "server literal end-to-end" ruling (v1.5.0 fix: TIME values carried as literals).
        return text;
    }

    /**
     * Extract the first quoted-string argument from a function call like
     * {@code TO_TIMESTAMP('12-10月-26 04.29.23.261316 上午')} or
     * {@code TO_DATE('2026-10-12','YYYY-MM-DD')}. The inner string is returned verbatim
     * (with '' escapes un-doubled); the target JDBC driver interprets it.
     */
    private static String extractFirstStringArg(String call) {
        // call starts with FUNC( and ends with ). Find the first ' after the (.
        int openParen = call.indexOf('(');
        if (openParen < 0) {
            throw new UnsupportedLiteralException("malformed function call: " + call);
        }
        int firstQuote = call.indexOf('\'', openParen);
        if (firstQuote < 0) {
            throw new UnsupportedLiteralException("no string argument in " + call);
        }
        // Walk to the closing quote, respecting '' escapes.
        int i = firstQuote + 1;
        while (i < call.length()) {
            char c = call.charAt(i);
            if (c == '\'') {
                if (i + 1 < call.length() && call.charAt(i + 1) == '\'') {
                    i += 2; // escaped quote
                    continue;
                }
                String inner = call.substring(firstQuote + 1, i);
                return inner.replace("''", "'");
            }
            i++;
        }
        throw new UnsupportedLiteralException("unterminated string in " + call);
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.length() % 2 == 0 ? hex : "0" + hex;
        int len = clean.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new UnsupportedLiteralException("invalid hex: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
