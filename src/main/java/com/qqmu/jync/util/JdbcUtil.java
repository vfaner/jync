package com.qqmu.jync.util;

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
}
