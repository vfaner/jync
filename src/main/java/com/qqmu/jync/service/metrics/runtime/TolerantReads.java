package com.qqmu.jync.service.metrics.runtime;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Several database families report each runtime figure through its own view, and the
 * monitoring role a user can spare is often allowed to read some of them and not others.
 * Reading them one at a time and tolerating the denials keeps the readable figures; a run
 * where nothing at all was readable is still reported as a failure, because a chart drawn
 * over all-null samples looks like zero load when it really means "cannot see".
 */
final class TolerantReads {

    @FunctionalInterface
    interface Query<T> {
        T run() throws SQLException;
    }

    private SQLException firstFailure;

    <T> T attempt(Query<T> query) {
        try {
            return query.run();
        } catch (SQLException e) {
            if (firstFailure == null) {
                firstFailure = e;
            }
            return null;
        }
    }

    void requireSomethingReadable(boolean any) throws SQLException {
        if (!any && firstFailure != null) {
            throw firstFailure;
        }
    }

    static Long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? parseLong(rs.getString(1)) : null;
        }
    }

    static Integer scalarInt(Connection connection, String sql) throws SQLException {
        Long value = scalarLong(connection, sql);
        return value == null || value > Integer.MAX_VALUE ? null : value.intValue();
    }

    static Double scalarDouble(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? parseDouble(rs.getString(1)) : null;
        }
    }

    static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A blank cell (SQL NULL, or a server that answered with something non-numeric) is unknown, not zero. */
    static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double hitPercent(Long hits, Long reads) {
        if (hits == null || reads == null || hits + reads <= 0) {
            return null;
        }
        return hits * 100.0 / (hits + reads);
    }
}
