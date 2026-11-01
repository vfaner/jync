package com.qqmu.jync.util;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import lombok.extern.slf4j.Slf4j;

/** Small shared JDBC helpers. */
@Slf4j
public final class JdbcUtil {

    private JdbcUtil() {
    }

    /**
     * Applies a query timeout when the caller asked for one. A driver that rejects the call is
     * left at its own default rather than failing the whole operation — the timeout is a safety
     * net, never a functional requirement.
     */
    public static void applyQueryTimeout(Statement statement, Integer timeoutSeconds) {
        if (statement == null || timeoutSeconds == null || timeoutSeconds <= 0) {
            return;
        }
        try {
            statement.setQueryTimeout(timeoutSeconds);
        } catch (SQLException e) {
            log.debug("Driver does not support setQueryTimeout: {}", e.getMessage());
        }
    }

    /**
     * The product's identifier quote string, or {@code ""} when it has none.
     *
     * <p>JDBC returns a single space for "this product does not quote identifiers"; drivers
     * may also return null, and a custom driver may fail the metadata call. All three mean
     * "leave the identifier bare".
     */
    public static String identifierQuote(Connection conn) {
        try {
            DatabaseMetaData metaData = conn.getMetaData();
            String quote = metaData == null ? null : metaData.getIdentifierQuoteString();
            return quote == null || quote.isBlank() ? "" : quote;
        } catch (SQLException e) {
            log.debug("Identifier quote unavailable: {}", e.getMessage());
            return "";
        }
    }

    /** One identifier wrapped in {@code quote}, any embedded quote character doubled. */
    public static String quotedIdentifier(String quote, String identifier) {
        if (quote == null || quote.isEmpty()) {
            return identifier;
        }
        // An embedded quote would otherwise close the quoted name early — a table named
        // a"b would break the generated identifier.
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    /** {@code schema.table}, each part quoted when the product supports quoting. */
    public static String qualifiedName(String quote, String schema, String table) {
        String quotedTable = quotedIdentifier(quote, table);
        return schema == null || schema.isBlank()
                ? quotedTable
                : quotedIdentifier(quote, schema) + "." + quotedTable;
    }

    /**
     * Removes a trailing statement terminator from a plain SQL statement.
     *
     * <p>Reviewers and models end a statement with {@code ;}, but a JDBC driver is expecting the
     * statement itself, not a script: Oracle (and the Oracle-compatible products) answer a
     * trailing semicolon on plain DDL with ORA-00911. Only plain statements may be passed here —
     * a PL/SQL block's {@code END;} is part of its syntax and must be left alone.
     *
     * @return the statement without trailing semicolons or surrounding whitespace; null stays null
     */
    public static String stripTrailingSemicolon(String sql) {
        if (sql == null) {
            return null;
        }
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    /**
     * Removes trailing SQL*Plus {@code /} lines from a submitted statement.
     *
     * <p>SQL Developer writes a PL/SQL block as the block followed by a lone {@code /} line. The
     * slash is a client command that submits the buffer; sent through JDBC it is invalid SQL and
     * Oracle answers ORA-00911. Only whole {@code /} lines are removed — unlike
     * {@link #stripTrailingSemicolon}, the block's own {@code END;} must survive.
     *
     * @return the statement without trailing slash lines or surrounding whitespace; null stays null
     */
    public static String stripTrailingSlash(String sql) {
        if (sql == null) {
            return null;
        }
        String s = sql.strip();
        while (!s.isEmpty()) {
            int lastBreak = s.lastIndexOf('\n');
            String lastLine = (lastBreak < 0 ? s : s.substring(lastBreak + 1)).strip();
            if (!"/".equals(lastLine)) {
                break;
            }
            s = lastBreak < 0 ? "" : s.substring(0, lastBreak).strip();
        }
        return s;
    }

    /**
     * Rolls back the connection (if open) and closes it, ignoring any SQLException.
     * Prepares a connection for reuse or disposal after an error.
     */
    public static void rollbackAndClose(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // Ignore rollback errors during cleanup
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // Ignore close errors during cleanup
        }
    }
}
