package com.qqmu.jync.dto;

import java.time.Instant;

/**
 * Lean projection of {@code sync_progress} for the table-status page.
 *
 * <p>Deliberately excludes {@code last_sync_value}: it is a LOB and the status endpoint is
 * polled every five seconds while a project runs. Loading the full entity for hundreds of
 * tables pulled every cursor value out of the metadata database to render no cursor text at
 * all.
 */
public record ProgressStatusRow(String objectName, Boolean initialLoadDone,
                                Long rowsSyncedTotal, Instant lastSyncTime,
                                String cursorStrategy, String cursorColumn,
                                Boolean resumableLoad, Boolean fullLoadWithoutCursor,
                                Long fullLoadTotalRows) {

    /** Compatibility constructor for callers/tests that do not carry the estimate. */
    public ProgressStatusRow(String objectName, Boolean initialLoadDone,
                             Long rowsSyncedTotal, Instant lastSyncTime,
                             String cursorStrategy, String cursorColumn,
                             Boolean resumableLoad, Boolean fullLoadWithoutCursor) {
        this(objectName, initialLoadDone, rowsSyncedTotal, lastSyncTime,
                cursorStrategy, cursorColumn, resumableLoad, fullLoadWithoutCursor, null);
    }
}
