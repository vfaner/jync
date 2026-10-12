package com.qqmu.jync.service.cdc;

import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * Classifies binlog stream failures into "retry makes sense" vs "retry is pointless,
 * the user must act" (task book B7). PURGED and SERVER_ID_CONFLICT park the project
 * in ERROR until a human rebaselines or fixes the id offset; everything else backs
 * off and reconnects.
 */
@Slf4j
public final class CdcFailureClassifier {

    public enum Kind { PURGED, SERVER_ID_CONFLICT, AUTH_FAILURE, TRANSIENT }

    private CdcFailureClassifier() { }

    public static Kind classify(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            String message = c.getMessage() == null
                    ? "" : c.getMessage().toLowerCase(Locale.ROOT);
            String typeName = c.getClass().getName().toLowerCase(Locale.ROOT);
            if (message.contains("could not find first log file name in binary log index file")
                    || message.contains("master has purged binary logs")
                    || message.contains("errno: 1236")
                    // MySQL 8.0 GTID-resume wording (ruling R3a): the 8.0.46 server rejects
                    // a replica whose GTID set needs purged logs with "Cannot replicate
                    // because the source purged required binary logs. Replicate the missing
                    // transactions from elsewhere, ..." — neither fragment matched before,
                    // so the fatal purge parked as TRANSIENT and hot-looped the reconnect.
                    || message.contains("purged required binary logs")
                    || message.contains("missing transactions")) {
                return Kind.PURGED;
            }
            if (message.contains("same server_id") || message.contains("same server_uuid")
                    || message.contains("a slave with the same server")) {
                return Kind.SERVER_ID_CONFLICT;
            }
            if (typeName.contains("authenticationexception") || message.contains("access denied")) {
                return Kind.AUTH_FAILURE;
            }
            // Oracle LogMiner ORA codes (v1.6.0). Scanned after the binlog checks so the
            // MySQL path is byte-for-byte unchanged; an ORA- prefix is unambiguous to Oracle.
            if (message.contains("ora-01291")          // missing logfile
                    || message.contains("ora-00310")    // archived log sequence mismatch
                    || message.contains("ora-00308")) { // cannot open archived log
                return Kind.PURGED;
            }
            if (message.contains("ora-01031")) {        // insufficient privileges
                return Kind.AUTH_FAILURE;
            }
        }
        return Kind.TRANSIENT;
    }

    /** Root-cause message, capped for storage in sync_task.last_sync_result and UI display. */
    public static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() != null ? root.getMessage() : root.getClass().getName();
        return message.length() > 500 ? message.substring(0, 500) + "…" : message;
    }
}
