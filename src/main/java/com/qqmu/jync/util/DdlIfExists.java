package com.qqmu.jync.util;

/**
 * Rewrites {@code DROP TABLE} / {@code DROP VIEW} to their idempotent form.
 *
 * <p>A skipped CDC DDL is a task replayed by hand, often after the target was already
 * repaired or the table never existed there (the source dropped a table this project
 * created only on the source side). A plain {@code DROP TABLE} then fails with "unknown
 * table" and the task stays red forever, so the stored suggestion and the executed
 * statement both carry {@code IF EXISTS} wherever the target understands it.
 *
 * <p>Only the statement head is touched; comments and a leading {@code USE} are preserved,
 * and {@code DROP INDEX} / {@code ALTER} / {@code CREATE} are returned unchanged. Callers
 * that target a dialect without {@code IF EXISTS} (Oracle, DM, YashanDB) strip it back
 * out and tolerate a missing object instead.
 */
public final class DdlIfExists {

    private DdlIfExists() {
    }

    /** The statement with {@code IF EXISTS} after TABLE/VIEW, or unchanged. */
    public static String add(String sql) {
        Head head = head(sql);
        if (head == null || alreadyIfExists(sql, head)) {
            return sql;
        }
        return sql.substring(0, head.keywordEnd) + " IF EXISTS"
                + sql.substring(head.keywordEnd);
    }

    /** The same statement without {@code IF EXISTS}, or unchanged. */
    public static String remove(String sql) {
        Head head = head(sql);
        if (head == null) {
            return sql;
        }
        int open = skipWhitespace(sql, head.keywordEnd);
        if (!wordAt(sql, open, "IF")) {
            return sql;
        }
        int afterIf = skipWhitespace(sql, open + 2);
        if (!wordAt(sql, afterIf, "EXISTS")) {
            return sql;
        }
        return sql.substring(0, head.keywordEnd) + sql.substring(afterIf + 6);
    }

    /** Whether this is a DROP TABLE / DROP VIEW, with or without IF EXISTS. */
    public static boolean isDropTableOrView(String sql) {
        return head(sql) != null;
    }

    private static boolean alreadyIfExists(String sql, Head head) {
        int open = skipWhitespace(sql, head.keywordEnd);
        if (!wordAt(sql, open, "IF")) {
            return false;
        }
        return wordAt(sql, skipWhitespace(sql, open + 2), "EXISTS");
    }

    /** End offset of the TABLE/VIEW keyword, plus where the statement head starts. */
    private record Head(int keywordEnd) {
    }

    private static Head head(String sql) {
        if (sql == null) {
            return null;
        }
        int i = skipNoise(sql, 0);
        if (!wordAt(sql, i, "DROP")) {
            return null;
        }
        int object = skipWhitespace(sql, i + 4);
        int end;
        if (wordAt(sql, object, "TABLE")) {
            end = object + 5;
        } else if (wordAt(sql, object, "VIEW")) {
            end = object + 4;
        } else {
            return null;
        }
        return new Head(end);
    }

    /** Skips whitespace, block/line comments and a leading {@code USE db;}. */
    private static int skipNoise(String sql, int from) {
        int i = from;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return sql.length();
                }
                i = end + 2;
            } else if (sql.startsWith("--", i) || c == '#') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    return sql.length();
                }
                i = end + 1;
            } else if (wordAt(sql, i, "USE")) {
                int semi = sql.indexOf(';', i + 3);
                if (semi < 0) {
                    return sql.length();
                }
                i = semi + 1;
            } else {
                break;
            }
        }
        return i;
    }

    private static int skipWhitespace(String sql, int from) {
        int i = from;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Whole-word, case-insensitive match; a following identifier character does not count. */
    private static boolean wordAt(String sql, int at, String word) {
        if (at < 0 || at + word.length() > sql.length()) {
            return false;
        }
        if (!sql.regionMatches(true, at, word, 0, word.length())) {
            return false;
        }
        int after = at + word.length();
        return after >= sql.length() || !isNameChar(sql.charAt(after));
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
