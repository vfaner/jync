package com.qqmu.jync.service.cdc;

import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * Classifies CDC stream failures across all four engines (MySQL binlog, Oracle
 * LogMiner, PG logical replication, SQL Server CDC) into "retry makes sense"
 * ({@code TRANSIENT} — back off and reconnect) vs "retry is pointless, a human must
 * act" (everything else — the consumer parks the project in ERROR): {@code PURGED} =
 * re-baseline from a fresh snapshot, {@code SERVER_ID_CONFLICT} = fix the id offset,
 * {@code AUTH_FAILURE} = fix credentials or grants, {@code SCHEMA_CONFLICT} = settle
 * the in-flight DDL (P2-13: previously mislabeled AUTH_FAILURE), {@code XA_ORDERING} =
 * the source interleaves XA branches in a way binlog capture cannot replay safely —
 * switch the project to polling or serialize the source's branches.
 */
@Slf4j
public final class CdcFailureClassifier {

    public enum Kind {
        PURGED, SERVER_ID_CONFLICT, AUTH_FAILURE, SCHEMA_CONFLICT, XA_ORDERING,
        TRANSACTION_TOO_LARGE, TRANSIENT
    }

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
                    || message.contains("missing transactions")
                    // #2: SQL Server — the sentinel SqlServerCdcConsumer throws when
                    // error 22054 hits an ESTABLISHED capture instance (start_lsn at or
                    // below the watermark): the CDC cleanup job purged change rows past
                    // it. Same remedy as the binlog purge — re-baseline from snapshot.
                    || message.contains("sqlserver cdc purge")
                    // PG logical decoding — the mirrored sentinel PgLogicalCdcConsumer
                    // throws when the slot is gone but a committed position exists (see
                    // ensureSlot). The WAL in between is unreachable; re-baseline.
                    || message.contains("pg cdc purge")) {
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
                    || message.contains("ora-00308")    // cannot open archived log
                    || message.contains("ora-00313")    // open failed for log member
                    || message.contains("ora-00312")    // companion text of 00313
                    // P2-5: DBMS_LOGMNR.START_LOG_MNR raises ORA-01403 "no data found"
                    // when NO redo covers the requested SCN range — the logs were
                    // recycled. Retry re-asks the same empty range forever; the remedy
                    // is the PURGED one: re-baseline from a fresh snapshot.
                    || message.contains("ora-01403")) {
                return Kind.PURGED;
            }
            if (message.contains("ora-01031")        // insufficient privileges
                    // ORA-65040: wrong container — mining session started in CDB$ROOT but
                    // executing against a PDB, or vice versa. Retrying with the same
                    // connection will never succeed; park it for the user to fix the
                    // connection config.
                    || message.contains("ora-65040")) {
                return Kind.AUTH_FAILURE;
            }
            // P2-13: ORA-01466 "unable to read data — table definition has changed" —
            // a DDL is mid-flight against the dictionary the mining session reads, and
            // retrying hot-loops on the same conflict until it settles. It is not an
            // auth problem: parking it under AUTH_FAILURE sent the DBA hunting
            // credentials. It gets its own kind.
            if (message.contains("ora-01466")) {
                return Kind.SCHEMA_CONFLICT;
            }
            // Source-side XA ordering Jync cannot replay safely (only serial two-phase
            // branches are supported): reconnecting replays the same stream and hits the
            // same condition, so backoff would hot-loop forever. Park it for a human —
            // the remedy is switching the project to polling or serializing the source's
            // XA branches, neither of which a retry can do.
            if (message.contains("unsupported xa transaction ordering")) {
                return Kind.XA_ORDERING;
            }
            // A transaction that exceeds the configured buffer cap: replaying it would
            // buffer the same rows again (previously until the heap died), so backoff
            // cannot help. Park it with a verdict naming the limits and the remedies.
            if (message.contains("cdc transaction exceeds the configured buffer limit")) {
                return Kind.TRANSACTION_TOO_LARGE;
            }
            // P2-5: ORA-01555 (snapshot too old — the undo backing the consistent read
            // was overwritten under load; a fresh mining session gets a fresh snapshot)
            // and ORA-00060 (deadlock detected) stay on the TRANSIENT fall-through by
            // DESIGN: backoff-retry is the correct remedy for both. Deliberately not
            // matched anywhere here, so nobody "helps" by parking them later.

            // —— PG / SQL Server (P1-2). SQLSTATE first: class 28 is the standard
            // invalid_authorization on BOTH vendors (PG 28P01 password / 28000, SQL
            // Server 18456 login failed); 42501 is PG insufficient_privilege.
            // Deliberately NOT matched: 53300 too_many_connections, 57P03
            // cannot_connect_now — those are resource exhaustion where backoff-retry
            // is exactly right, so they fall through to TRANSIENT.
            if (c instanceof java.sql.SQLException) {
                String state = ((java.sql.SQLException) c).getSQLState();
                if (state != null) {
                    String upperState = state.toUpperCase(Locale.ROOT);
                    if (upperState.startsWith("28") || upperState.equals("42501")) {
                        return Kind.AUTH_FAILURE;
                    }
                }
            }
            if (message.contains("password authentication failed")
                    || message.contains("no pg_hba.conf entry")
                    // SQL Server wording (18456 / 229 / 230) — SQLState survives most
                    // wrappers, but message matching covers the ones that drop it:
                    || message.contains("login failed for user")
                    || message.contains("permission was denied")
                    // PG slot exhaustion: retrying can never succeed; a human must
                    // drop unused slots (P1-9's quota check catches this earlier).
                    || message.contains("maximum number of logical replication slots")
                    || message.contains("number of requested standby slots exceeds")
                    || message.contains("max_replication_slots")) {
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
