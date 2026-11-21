package com.qqmu.jync.service.etl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.qqmu.jync.model.DatabaseType.DialectFamily;

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

    /**
     * The scanning rules of callers that do not know their source dialect. MySQL is the
     * historic behaviour (backslash escapes a quote) and stays the default so the no-family
     * overloads keep their meaning.
     */
    private static final DialectFamily DEFAULT_FAMILY = DialectFamily.MYSQL;

    private EtlSqlScript() {
    }

    /** Refuses a script that cannot run: unterminated syntax, nothing to run, or no rows. */
    static void validate(String sql) {
        validate(sql, DEFAULT_FAMILY);
    }

    static void validate(String sql, DialectFamily family) {
        List<String> statements = split(sql, family);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException(EMPTY);
        }
        if (statements.size() > 1
                && !returnsRows(statements.get(statements.size() - 1), family)) {
            throw new IllegalArgumentException(LAST_STATEMENT);
        }
    }

    /** The row-returning statement of the script, without its trailing semicolon. */
    static String lastStatement(String sql) {
        return lastStatement(sql, DEFAULT_FAMILY);
    }

    static String lastStatement(String sql, DialectFamily family) {
        List<String> statements = split(sql, family);
        return statements.isEmpty() ? null : statements.get(statements.size() - 1);
    }

    /** Whether the statement starts with a {@code WITH} clause, after any comments. */
    static boolean startsWithWith(String statement) {
        return startsWithWith(statement, DEFAULT_FAMILY);
    }

    static boolean startsWithWith(String statement, DialectFamily family) {
        List<Token> tokens = topLevelTokens(statement, family);
        return !tokens.isEmpty() && isWord(tokens.get(0).word(), "WITH");
    }

    /**
     * Whether the statement carries a real top-level row-limiting clause ({@code TOP},
     * {@code FETCH FIRST/NEXT}, {@code LIMIT/OFFSET … ROWS}, {@code FOR UPDATE/XML/JSON}).
     *
     * <p>The resume wrapper puts the extraction inside a derived table and filters on the key
     * outside it, so an inner limit is evaluated before the watermark: with a checkpoint the
     * same first window is re-read, filtered out completely, and the remaining rows are never
     * migrated. Validation refuses such a task instead of letting it report SUCCESS forever.
     */
    static boolean hasTopLevelLimitingClause(String statement, DialectFamily family) {
        return hasLimitingClause(statement, topLevelTokens(statement, family));
    }

    /** Whether the statement has an {@code ORDER BY} outside every parentheses level. */
    static boolean hasTopLevelOrderBy(String statement) {
        return hasTopLevelOrderBy(statement, DEFAULT_FAMILY);
    }

    static boolean hasTopLevelOrderBy(String statement, DialectFamily family) {
        return orderByStart(topLevelTokens(statement, family)) >= 0;
    }

    /**
     * Removes a top-level {@code ORDER BY} so the statement can sit inside a derived table.
     *
     * <p>SQL Server refuses {@code ORDER BY} in a derived table unless {@code TOP}, {@code OFFSET}
     * or {@code FOR XML/JSON} makes it part of the row selection; DB2 has the same restriction
     * unless a {@code FETCH FIRST} clause is present. The caller only applies this to those
     * families. A statement that carries such a limiting clause is returned unchanged: removing
     * its order would change which rows it selects.
     */
    static String stripTopLevelOrderBy(String statement) {
        return stripTopLevelOrderBy(statement, DEFAULT_FAMILY);
    }

    static String stripTopLevelOrderBy(String statement, DialectFamily family) {
        List<Token> tokens = topLevelTokens(statement, family);
        if (hasLimitingClause(statement, tokens)) {
            return statement;
        }
        int start = orderByStart(tokens);
        return start < 0 ? statement : statement.substring(0, start).stripTrailing();
    }

    private static int orderByStart(List<Token> tokens) {
        int start = -1;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (isWord(tokens.get(i).word(), "ORDER")
                    && isWord(tokens.get(i + 1).word(), "BY")) {
                start = tokens.get(i).start();
            }
        }
        return start;
    }

    /**
     * Whether a real row-limiting clause is present. Bare keywords are not enough: DB2's
     * {@code FOR FETCH ONLY} / {@code OPTIMIZE FOR n ROWS} and an unquoted alias such as
     * {@code AS limit} limit nothing, yet keeping their ORDER BY would make the derived-table
     * wrapper fail on every run. TOP, FETCH FIRST/NEXT, LIMIT/OFFSET with a count, and
     * FOR UPDATE / FOR XML / FOR JSON (cursor or document semantics) do keep the order.
     */
    private static boolean hasLimitingClause(String statement, List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String word = tokens.get(i).word();
            String next = i + 1 < tokens.size() ? tokens.get(i + 1).word() : null;
            if (isWord(word, "TOP")) {
                // TOP needs a count to limit anything: `AS top` is just an alias. The count can
                // be a number, a variable, or a parenthesized expression — all of which the
                // character right after the word reveals, even when the tokenizer drops it.
                if (nextCharIsCount(statement, tokens.get(i).end())) {
                    return true;
                }
                continue;
            }
            if (isWord(word, "FETCH") && (isWord(next, "FIRST") || isWord(next, "NEXT"))) {
                return true;
            }
            if (isWord(word, "FOR")
                    && (isWord(next, "UPDATE") || isWord(next, "XML") || isWord(next, "JSON"))) {
                return true;
            }
            if (isWord(word, "LIMIT")
                    && (next == null || isAllDigits(next)
                        || isWord(next, "ALL") || isWord(next, "ROWS"))) {
                return true;
            }
            // SQL Server / DB2 pagination is OFFSET <count> ROWS: the count is one token
            // (decimal, @variable, :host variable or hex), so ROWS sits two tokens on. Keying
            // on that pair rather than a bare OFFSET word avoids mistaking columns literally
            // named "offset, rows" for the clause.
            if (isWord(word, "OFFSET")
                    && i + 2 < tokens.size() && isWord(tokens.get(i + 2).word(), "ROWS")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllDigits(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isDigit(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code from} is followed (after whitespace) by a row-count expression. */
    private static boolean nextCharIsCount(String statement, int from) {
        int i = from;
        while (i < statement.length() && Character.isWhitespace(statement.charAt(i))) {
            i++;
        }
        if (i >= statement.length()) {
            return false;
        }
        char c = statement.charAt(i);
        return Character.isDigit(c) || c == '(' || c == '@' || c == ':' || c == '?';
    }

    private static boolean isWord(String token, String keyword) {
        return token != null && token.equalsIgnoreCase(keyword);
    }

    /**
     * Identifier-like words outside parentheses, quotes and comments, with their offsets.
     *
     * <p>Keyword checks must not see text inside a literal, a quoted identifier, a comment or a
     * nested expression — a window function's {@code OVER (ORDER BY ...)} is not a statement-level
     * order, and a column alias {@code "ORDER"} is not a clause.
     */
    static List<Token> topLevelTokens(String sql) {
        return topLevelTokens(sql, DEFAULT_FAMILY);
    }

    static List<Token> topLevelTokens(String sql, DialectFamily family) {
        List<Token> tokens = new ArrayList<>();
        if (sql == null) {
            return tokens;
        }
        StringBuilder sink = new StringBuilder();
        int depth = 0;
        int i = 0;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            char next = i + 1 < length ? sql.charAt(i + 1) : '\0';
            if ((c == 'q' || c == 'Q') && next == '\'' && i + 2 < length
                    && (i == 0 || !isNameChar(sql.charAt(i - 1)))) {
                i = appendAlternativeQuoted(sql, i, clear(sink));
            } else if (c == '\'' || c == '"' || c == '`') {
                i = appendQuoted(sql, i, c, clear(sink), backslashEscapes(family));
            } else if (c == '[') {
                i = appendBracketed(sql, i, clear(sink));
            } else if (c == '-' && next == '-') {
                i = appendLineComment(sql, i, clear(sink));
            } else if (c == '#' && hashComments(family)) {
                i = appendLineComment(sql, i, clear(sink));
            } else if (c == '/' && next == '*') {
                i = appendBlockComment(sql, i, clear(sink), nestedBlockComments(family));
            } else if (c == '$' && startsDollarQuote(sql, i)) {
                i = appendDollarQuoted(sql, i, clear(sink));
            } else if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                i++;
            } else if (isNameChar(c)) {
                int start = i;
                while (i < length && isNameChar(sql.charAt(i))) {
                    i++;
                }
                if (depth == 0) {
                    tokens.add(new Token(sql.substring(start, i), start, i));
                }
            } else {
                i++;
            }
        }
        return tokens;
    }

    private static StringBuilder clear(StringBuilder sink) {
        sink.setLength(0);
        return sink;
    }

    /** One top-level identifier-like word and the offsets it occupies in the statement. */
    record Token(String word, int start, int end) {
    }

    /** The statements of the script, in order, without their separating semicolons. */
    static List<String> split(String sql) {
        return split(sql, DEFAULT_FAMILY);
    }

    static List<String> split(String sql, DialectFamily family) {
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
                i = appendQuoted(sql, i, c, current, backslashEscapes(family));
            } else if (c == '[') {
                content = true;
                i = appendBracketed(sql, i, current);
            } else if (c == '-' && next == '-') {
                i = appendLineComment(sql, i, current);
            } else if (c == '#' && hashComments(family)) {
                i = appendLineComment(sql, i, current);
            } else if (c == '/' && next == '*') {
                i = appendBlockComment(sql, i, current, nestedBlockComments(family));
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

    private static int appendQuoted(String sql, int start, char quote, StringBuilder out,
                                    boolean backslashEscapes) {
        out.append(quote);
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\' && i + 1 < sql.length()) {
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

    private static int appendBlockComment(String sql, int start, StringBuilder out,
                                          boolean nested) {
        out.append("/*");
        int depth = 1;
        int i = start + 2;
        while (i < sql.length()) {
            if (sql.charAt(i) == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
                out.append("*/");
                i += 2;
                depth--;
                if (depth == 0) {
                    return i;
                }
            } else if (nested && sql.charAt(i) == '/' && i + 1 < sql.length()
                    && sql.charAt(i + 1) == '*') {
                out.append("/*");
                i += 2;
                depth++;
            } else {
                out.append(sql.charAt(i));
                i++;
            }
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
    private static boolean returnsRows(String statement, DialectFamily family) {
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
            } else if (c == '#' && hashComments(family)) {
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

    /** MySQL lets a backslash escape the closing quote; standard SQL does not. */
    private static boolean backslashEscapes(DialectFamily family) {
        return family == DialectFamily.MYSQL;
    }

    /** MySQL uses {@code #} for a line comment; SQL Server uses it in local temp names. */
    private static boolean hashComments(DialectFamily family) {
        return family == DialectFamily.MYSQL;
    }

    /** PostgreSQL and SQL Server let a block comment contain another one. */
    private static boolean nestedBlockComments(DialectFamily family) {
        return family == DialectFamily.POSTGRES || family == DialectFamily.SQLSERVER;
    }
}
