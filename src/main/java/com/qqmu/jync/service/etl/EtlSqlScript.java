package com.qqmu.jync.service.etl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The extraction box may hold a script: several statements executed in order on one source
 * connection, the last one supplying the rows. Splitting is textual and conservative — a
 * semicolon inside a string, a quoted or bracketed identifier, or a comment never separates
 * statements — because a wrong split sends half a statement to the server. What remains
 * outside such a construct is split and trimmed, and empty or comment-only pieces are dropped
 * so a trailing {@code ;} or a leading comment does not become a statement of its own.
 */
final class EtlSqlScript {

    private static final String INVALID = "error.etl.script.invalid";
    private static final String EMPTY = "error.etl.script.empty";
    private static final String LAST_STATEMENT = "error.etl.script.lastStatement";

    private EtlSqlScript() {
    }

    /** Refuses a script that cannot run: unterminated syntax, nothing to run, or no rows. */
    static void validate(String sql) {
        List<String> statements = split(sql);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException(EMPTY);
        }
        if (statements.size() > 1 && !returnsRows(statements.get(statements.size() - 1))) {
            throw new IllegalArgumentException(LAST_STATEMENT);
        }
    }

    /** The statements of the script, in order, without their separating semicolons. */
    static List<String> split(String sql) {
        if (sql == null) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean content = false;
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            char next = i + 1 < length ? sql.charAt(i + 1) : '\0';
            if ((c == 'q' || c == 'Q') && next == '\'' && i + 2 < length
                    && (i == 0 || !isNameChar(sql.charAt(i - 1)))) {
                content = true;
                i = appendAlternativeQuoted(sql, i, current);
            } else if (c == '\'' || c == '"' || c == '`') {
                content = true;
                i = appendQuoted(sql, i, c, current);
            } else if (c == '[') {
                content = true;
                i = appendBracketed(sql, i, current);
            } else if (c == '-' && next == '-') {
                i = appendLineComment(sql, i, current);
            } else if (c == '/' && next == '*') {
                i = appendBlockComment(sql, i, current);
            } else if (c == '$' && startsDollarQuote(sql, i)) {
                content = true;
                i = appendDollarQuoted(sql, i, current);
            } else if (c == ';') {
                addStatement(statements, current, content);
                current.setLength(0);
                content = false;
                i++;
            } else {
                if (!Character.isWhitespace(c)) {
                    content = true;
                }
                current.append(c);
                i++;
            }
        }
        addStatement(statements, current, content);
        return statements;
    }

    private static int appendQuoted(String sql, int start, char quote, StringBuilder out) {
        out.append(quote);
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < sql.length()) {
                // MySQL honours the backslash escape; keeping the pair together never splits.
                out.append(c).append(sql.charAt(i + 1));
                i += 2;
            } else if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    out.append(c).append(c);
                    i += 2;
                } else {
                    out.append(c);
                    return i + 1;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        throw new IllegalArgumentException(INVALID);
    }

    private static int appendBracketed(String sql, int start, StringBuilder out) {
        out.append('[');
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == ']') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == ']') {
                    out.append("]]");
                    i += 2;
                } else {
                    out.append(c);
                    return i + 1;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        throw new IllegalArgumentException(INVALID);
    }

    /**
     * Oracle's alternative quoting ({@code q'[...]'}) wraps a literal that may contain
     * apostrophes; the closing marker is the delimiter plus {@code '}. The body is kept as is.
     */
    private static int appendAlternativeQuoted(String sql, int start, StringBuilder out) {
        char delimiter = sql.charAt(start + 2);
        char close = switch (delimiter) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> delimiter;
        };
        out.append(sql, start, start + 3);
        int i = start + 3;
        while (i + 1 < sql.length()) {
            if (sql.charAt(i) == close && sql.charAt(i + 1) == '\'') {
                out.append(close).append('\'');
                return i + 2;
            }
            out.append(sql.charAt(i));
            i++;
        }
        throw new IllegalArgumentException(INVALID);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    /**
     * PostgreSQL dollar quoting ({@code $$...$$}, {@code $tag$...$tag$}) keeps a body verbatim,
     * semicolons included. It only starts at a token boundary: a {@code $} inside an identifier
     * (Oracle and SQL Server allow that) stays part of the name.
     */
    private static boolean startsDollarQuote(String sql, int index) {
        if (index > 0 && isNameChar(sql.charAt(index - 1))) {
            return false;
        }
        int tagEnd = sql.indexOf('$', index + 1);
        if (tagEnd < 0) {
            return false;
        }
        String tag = sql.substring(index + 1, tagEnd);
        return tag.isEmpty() || tag.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static int appendDollarQuoted(String sql, int start, StringBuilder out) {
        int tagEnd = sql.indexOf('$', start + 1);
        String delimiter = sql.substring(start, tagEnd + 1);
        int close = sql.indexOf(delimiter, tagEnd + 1);
        if (close < 0) {
            throw new IllegalArgumentException(INVALID);
        }
        int end = close + delimiter.length();
        out.append(sql, start, end);
        return end;
    }

    private static int appendLineComment(String sql, int start, StringBuilder out) {
        int i = start;
        while (i < sql.length() && sql.charAt(i) != '\n') {
            out.append(sql.charAt(i));
            i++;
        }
        return i;
    }

    private static int appendBlockComment(String sql, int start, StringBuilder out) {
        out.append("/*");
        int i = start + 2;
        while (i < sql.length()) {
            if (sql.charAt(i) == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
                out.append("*/");
                return i + 2;
            }
            out.append(sql.charAt(i));
            i++;
        }
        throw new IllegalArgumentException(INVALID);
    }

    private static void addStatement(List<String> statements, StringBuilder current, boolean content) {
        if (!content) {
            return;
        }
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    /** Whether a statement can supply rows: it starts with SELECT or WITH after comments. */
    private static boolean returnsRows(String statement) {
        int i = 0;
        int length = statement.length();
        while (i < length) {
            char c = statement.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                i++;
            } else if (c == '-' && i + 1 < length && statement.charAt(i + 1) == '-') {
                while (i < length && statement.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < length && statement.charAt(i + 1) == '*') {
                int end = statement.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else {
                break;
            }
        }
        int end = i;
        while (end < length && Character.isLetter(statement.charAt(end))) {
            end++;
        }
        String keyword = statement.substring(i, end).toUpperCase(Locale.ROOT);
        return "SELECT".equals(keyword) || "WITH".equals(keyword);
    }
}
