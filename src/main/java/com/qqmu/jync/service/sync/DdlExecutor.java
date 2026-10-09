package com.qqmu.jync.service.sync;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
            "undefined table", "unknown object",
            // 达梦 DM / 金仓 KingBase 等国产库的中文"不存在"/"无效对象"错误
            "不存在", "未找到", "无法找到", "无效的表或视图名"
    };

    /** Substrings that indicate "the thing I tried to create is already there". */
    private static final String[] BENIGN_CREATE_MARKERS = {
            // No bare "exists": an unrelated error merely mentioning existence ("foreign key
            // target does not exist") must not be laundered into "create succeeded".
            "already exists", "duplicate", "name is already used",
            // SQL Server: "There is already an object named 'x' in the database."
            "already an object named",
            // 达梦 DM / 金仓 KingBase 等国产库的中文"已存在"错误
            "已存在", "已被使用"
    };

    private final Connection connection;

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
            return true;
        } catch (SQLException e) {
            if (tolerateMissing && isBenign(e, false)) {
                log.debug("Tolerating expected DDL failure: {}", e.getMessage());
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
            return true;
        } catch (SQLException e) {
            if (isBenign(e, true)) {
                log.debug("Object already exists, treating as success: {}", e.getMessage());
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

    /**
     * @param create true for the CREATE path ("already there" is benign), false for a
     *               tolerated DROP ("was not there" is benign)
     */
    private boolean isBenign(SQLException e, boolean create) {
        String message = e.getMessage();
        String lower = message == null ? "" : message.toLowerCase();
        String[] markers = create ? BENIGN_CREATE_MARKERS : BENIGN_DROP_MARKERS;
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        // SQLState 42S02 / 42P01 / 42704: undefined object. 42S01 / 42P07 / 42710: duplicate.
        String state = e.getSQLState();
        if (state == null) {
            return false;
        }
        if (!create) {
            return state.equals("42S02") || state.equals("42P01") || state.equals("42704");
        }
        if (state.equals("42S01") || state.equals("42P07") || state.equals("42710")) {
            return true;
        }
        // SQL Server maps vendor errors 2714/2759 ("already an object named") to S0001, but
        // S0001 is also returned for unrelated table errors — accept it only with the vendor
        // code or the "already" text that identifies the duplicate case.
        return state.equals("S0001")
                && (e.getErrorCode() == 2714 || e.getErrorCode() == 2759
                || lower.contains("already"));
    }

    private String abbreviate(String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
