package com.qqmu.jync.model;

/**
 * How a project captures source-side changes (task book v1.5.0, C1 三策略卡片).
 *
 * <p>Stored inside the project's {@code sync_config} JSON. Absent/null deserializes to
 * {@link #POLLING} so projects saved by v1.4.0 or older keep their exact behavior.
 */
public enum CaptureStrategy {
    /** Periodic cursor-based polling (timestamp / identity / ORA_ROWSCN, auto-resolved). */
    POLLING,
    /** Periodic full-table compare: every cycle re-reads both sides and reconciles. */
    FULL_COMPARE,
    /**
     * MySQL binlog CDC: a long-running consumer thread replays row events. Only offered
     * when the source {@code DatabaseType == MYSQL} and the capability probe passes.
     */
    BINLOG_CDC,
    /**
     * Oracle LogMiner CDC: a long-running consumer mines archived redo via DBMS_LOGMNR and
     * replays row events. Only offered when the source {@code DatabaseType == ORACLE} and
     * the 7-item readiness probe passes. On a CDB the mining session runs against
     * CDB$ROOT as a common user and rows are filtered by {@code SRC_CON_ID}; on a
     * non-CDB the project's own source connection mines directly.
     */
    LOGMINER_CDC;

    /**
     * Whether this strategy replays changes from a source-native log stream (binlog /
     * LogMiner) — as opposed to row-query polling or a full-table compare. Used to gate
     * long-running consumer threads, skip Quartz scheduling, and drive the CDC-specific
     * operations (probe / rebaseline).
     */
    public boolean isLogCdc() {
        return this == BINLOG_CDC || this == LOGMINER_CDC;
    }
}
