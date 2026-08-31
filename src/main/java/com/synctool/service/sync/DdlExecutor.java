package com.synctool.service.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs DDL against the target, distinguishing genuine failures from the benign ones.
 *
 * <p>Several dialects implement "create or replace" as a DROP followed by a CREATE, and the
 * DROP legitimately fails the first time because the object does not exist yet. Treating
 * every SQLException as fatal would make those objects permanently unsyncable, so the
 * executor classifies errors and lets tolerable ones pass.
 */
@Slf4j
public class DdlExecutor {

    /** Substrings that indicate "the thing I tried to drop was not there". */
    private static final String[] BENIGN_DROP_MARKERS = {
            "does not exist", "doesn't exist", "not exist", "unknown table",
            "cannot drop", "no such table", "not found", "invalid object name",
            "undefined table", "unknown object"
    };

    /** Substrings that indicate "the thing I tried to create is already there". */
    private static final String[] BENIGN_CREATE_MARKERS = {
            "already exists", "duplicate", "name is already used", "exists"
    };

    private final Connection connection;

    @Getter
    private int executed;

    @Getter
    private int tolerated;

    public DdlExecutor(Connection connection) {
        this.connection = connection;
    }

    /**
     * Executes one DDL statement.
     *
     * @param tolerateMissing when true, an error meaning "object absent" is not an error;
     *                        used for the DROP half of a replace pair
     * @return true when the statement ran, false when it failed tolerably
     * @throws SQLException when the failure is genuine
     */
    public boolean execute(String sql, boolean tolerateMissing) throws SQLException {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        try (Statement st = connection.createStatement()) {
            log.debug("Executing DDL: {}", abbreviate(sql));
            st.execute(sql);
            executed++;
            return true;
        } catch (SQLException e) {
            if (tolerateMissing && isBenign(e, BENIGN_DROP_MARKERS)) {
                log.debug("Tolerating expected DDL failure: {}", e.getMessage());
                tolerated++;
                return false;
            }
            // Rethrow with the statement attached; a bare driver message rarely identifies
            // which object failed.
            throw new SQLException("DDL failed: " + abbreviate(sql) + " -> " + e.getMessage(),
                    e.getSQLState(), e.getErrorCode(), e);
        }
    }

    /**
     * Executes a create-or-replace sequence. Every statement but the last is treated as a
     * preparatory DROP whose absence-failure is tolerated.
     */
    public void executeReplaceSequence(List<String> statements) throws SQLException {
        if (statements == null || statements.isEmpty()) {
            return;
        }
        for (int i = 0; i < statements.size(); i++) {
            boolean isLast = i == statements.size() - 1;
            execute(statements.get(i), !isLast);
        }
    }

    /**
     * Executes a CREATE whose "already exists" failure is acceptable — the object being
     * present is the desired end state, which keeps structure sync idempotent on replay.
     */
    public boolean executeIdempotentCreate(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            log.debug("Executing idempotent DDL: {}", abbreviate(sql));
            st.execute(sql);
            executed++;
            return true;
        } catch (SQLException e) {
            if (isBenign(e, BENIGN_CREATE_MARKERS)) {
                log.debug("Object already exists, treating as success: {}", e.getMessage());
                tolerated++;
                return false;
            }
            throw new SQLException("DDL failed: " + abbreviate(sql) + " -> " + e.getMessage(),
                    e.getSQLState(), e.getErrorCode(), e);
        }
    }

    /** Runs a statement whose failure never matters, e.g. toggling constraint checks. */
    public void executeQuietly(String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            log.debug("Optional statement failed ({}): {}", abbreviate(sql), e.getMessage());
        }
    }

    private boolean isBenign(SQLException e, String[] markers) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        // SQLState 42S02 / 42P01: undefined table. 42S01 / 42P07: duplicate table.
        String state = e.getSQLState();
        if (state != null) {
            if (markers == BENIGN_DROP_MARKERS
                    && (state.equals("42S02") || state.equals("42P01") || state.equals("42704"))) {
                return true;
            }
            if (markers == BENIGN_CREATE_MARKERS
                    && (state.equals("42S01") || state.equals("42P07") || state.equals("42710"))) {
                return true;
            }
        }
        return false;
    }

    private String abbreviate(String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
