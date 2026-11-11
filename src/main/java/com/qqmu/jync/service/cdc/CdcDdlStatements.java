package com.qqmu.jync.service.cdc;

import java.util.List;
import java.util.Locale;

/**
 * Which binlog Query statements change a table's structure.
 *
 * <p>MySQL writes more than table DDL to the binlog as Query events: privileges, account
 * management, maintenance and database creation are statements too. Filing those as
 * skipped-DDL tasks turns every {@code FLUSH PRIVILEGES} into an "action required" row,
 * forces the column projection on, and reports a replication gap that does not exist.
 *
 * <p>The test is a deny-list, not an allow-list: an unrecognised statement is still filed.
 * Missing a real structural change is far worse than filing one maintenance statement, so
 * only prefixes that can never alter a tracked table's columns are excluded.
 */
final class CdcDdlStatements {

    /** Statement prefixes that never change a tracked table's structure. */
    private static final List<String> NON_STRUCTURAL = List.of(
            "GRANT ",
            "REVOKE ",
            "ANALYZE ",
            "OPTIMIZE ",
            "REPAIR ",
            "FLUSH ",
            "CHECK ",
            "CHECKSUM ",
            "CREATE USER ",
            "ALTER USER ",
            "DROP USER ",
            "RENAME USER ",
            "CREATE ROLE ",
            "ALTER ROLE ",
            "DROP ROLE ",
            "SET PASSWORD ",
            "SET DEFAULT ROLE ",
            "SET ROLE ",
            "CREATE DATABASE ",
            "CREATE SCHEMA ",
            "ALTER DATABASE ",
            "ALTER SCHEMA ",
            // A dropped database cannot be represented as a per-table task; the execute
            // dialog would run the statement verbatim on the target connection, where it
            // can delete a same-named database.
            "DROP DATABASE ",
            "DROP SCHEMA ",
            "CREATE TABLESPACE ",
            "ALTER TABLESPACE ",
            "DROP TABLESPACE ",
            "CREATE SERVER ",
            "ALTER SERVER ",
            "DROP SERVER ",
            "INSTALL PLUGIN ",
            "UNINSTALL PLUGIN ",
            "INSTALL COMPONENT ",
            "UNINSTALL COMPONENT ",
            "ALTER INSTANCE ",
            "CREATE LOGFILE GROUP ",
            "ALTER LOGFILE GROUP ",
            "DROP LOGFILE GROUP ");

    private CdcDdlStatements() {
    }

    /** One identifier, quoted or plain, as the parser of a statement head needs it. */
    private static final String IDENT =
            "(?:`[^`]+`|\"[^\"]+\"|\\[[^\\]]+\\]|[A-Za-z0-9_$]+)";

    private static final java.util.regex.Pattern USE_PREFIX = java.util.regex.Pattern.compile(
            "(?is)^USE\\s+" + IDENT + "\\s*;?\\s*");

    /**
     * Drops the leading comments and {@code USE} prefixes a statement may carry.
     *
     * <p>The binlog QUERY text keeps the comments the client sent, and a multi-statement
     * batch can prefix {@code USE db;}. Both sit in front of the head keyword, so every
     * rule anchored on {@code ^ALTER / ^RENAME / ^DROP} silently stopped matching: the
     * schema was never rewritten, the table never mapped and the clauses never read.
     *
     * <p>Versioned comments are not comments: {@code /*!NNNNN ...} (MySQL) and
     * {@code /*M!NNNNN ...} (MariaDB) wrap SQL the server actually executed. Their content
     * is unwrapped (the version guard dropped) rather than discarded, or a wrapped
     * {@code ALTER TABLE} would strip to an empty statement — filed as a structural change
     * with no table, while the schema rewrite and table mapping saw nothing at all.
     */
    static String stripLeadingNoise(String sql) {
        String statement = sql == null ? "" : sql;
        while (true) {
            statement = statement.stripLeading();
            if (statement.startsWith("/*")) {
                int open;
                boolean executable;
                if (statement.startsWith("/*!")) {
                    open = 3;
                    executable = true;
                } else if (statement.regionMatches(true, 0, "/*M!", 0, 4)) {
                    open = 4;
                    executable = true;
                } else {
                    open = 2;
                    executable = false;
                }
                int end = statement.indexOf("*/", open);
                if (end < 0) {
                    return statement;
                }
                String body = statement.substring(open, end);
                String rest = statement.substring(end + 2);
                if (!executable) {
                    statement = rest;
                    continue;
                }
                // Only the version guard is not SQL: digits, optionally led by spaces.
                int content = 0;
                while (content < body.length() && Character.isWhitespace(body.charAt(content))) {
                    content++;
                }
                while (content < body.length() && Character.isDigit(body.charAt(content))) {
                    content++;
                }
                statement = body.substring(content) + " " + rest;
                continue;
            }
            if (statement.startsWith("--") || statement.startsWith("#")) {
                int end = statement.indexOf('\n');
                if (end < 0) {
                    return statement;
                }
                statement = statement.substring(end + 1);
                continue;
            }
            java.util.regex.Matcher use = USE_PREFIX.matcher(statement);
            if (use.find()) {
                statement = statement.substring(use.end());
                continue;
            }
            return statement;
        }
    }

    static boolean changesTableStructure(String sql) {
        String upper = stripLeadingNoise(sql).trim().toUpperCase(Locale.ROOT);
        while (upper.endsWith(";")) {
            upper = upper.substring(0, upper.length() - 1).trim();
        }
        for (String prefix : NON_STRUCTURAL) {
            if (upper.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
