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
}
