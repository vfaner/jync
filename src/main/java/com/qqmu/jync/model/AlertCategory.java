package com.qqmu.jync.model;

/**
 * The faults an administrator can ask to be told about.
 *
 * <p>Every category maps to a check the alert evaluator can answer from state the application
 * already keeps; nothing here changes how a sync, CDC stream or ETL task runs.
 */
public enum AlertCategory {

    /** The same table failed in several consecutive polling cycles. */
    SYNC_FAILURE("alert.category.syncFailure"),

    /** A polling cycle has made no progress for the stuck threshold. */
    POLL_STUCK("alert.category.pollStuck"),

    /** An enabled polling project has no registered schedule and is not running. */
    PROJECT_NOT_RUNNING("alert.category.projectNotRunning"),

    /** A CDC consumer is parked after a fatal failure, or has been disconnected for a while. */
    CDC_STOPPED("alert.category.cdcStopped"),

    /** The row-count audit found the target short of rows and started a repair. */
    DATA_DRIFT("alert.category.dataDrift"),

    /** A configured connection failed several consecutive health probes. */
    DB_UNHEALTHY("alert.category.dbUnhealthy"),

    /** A PostgreSQL logical slot is holding back more WAL than the configured ceiling. */
    PG_SLOT_BACKLOG("alert.category.pgSlotBacklog"),

    /**
     * A DDL statement was seen in a CDC stream and skipped. The target's structure is now
     * behind the source's, so the next row carrying a new column can fail the whole stream.
     * Announced as an event: nothing "recovers" until an operator aligns the two schemas.
     */
    DDL_SKIPPED("alert.category.ddlSkipped");

    private final String messageKey;

    AlertCategory(String messageKey) {
        this.messageKey = messageKey;
    }

    /** i18n key of the category's display name. */
    public String messageKey() {
        return messageKey;
    }

    /**
     * Whether this category describes a condition that ends, or a single event.
     *
     * <p>A condition is notified once when it starts, repeated after the cooldown, and answered
     * with a recovery message when it disappears. An event (a detected drift or a skipped DDL)
     * has no recovery: the repair it triggers either works or shows up as a sync failure of its
     * own, and the count only ever grows until the consumer restarts.
     */
    public boolean isEvent() {
        return this == DATA_DRIFT || this == DDL_SKIPPED;
    }

    /** Comma-separated form used by the settings row. */
    public static String toCsv(Iterable<AlertCategory> categories) {
        StringBuilder csv = new StringBuilder();
        for (AlertCategory category : categories) {
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(category.name());
        }
        return csv.toString();
    }
}
