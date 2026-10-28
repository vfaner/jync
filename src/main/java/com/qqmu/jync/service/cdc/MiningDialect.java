package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * One LogMiner implementation's discovery and command dialect.
 *
 * <p>{@link LogMinerSession} owns what is genuinely shared — the V$LOGMNR_CONTENTS cursor,
 * RS_ID continuation merging, the batch cap, END_LOGMNR on close. Everything a vendor
 * actually does differently lives here: which catalog says how far the redo goes, which
 * files can be mined at all, how the package is invoked, and what session settings the
 * mining session needs.
 *
 * <p>Measured differences (docs/dm-logminer-spike.md):
 * <ul>
 *   <li>Oracle can add the CURRENT online redo member; DM cannot ({@code [-4546] File open
 *       failure}) and can only mine archived files;</li>
 *   <li>Oracle reports the high-water mark as {@code V$DATABASE.CURRENT_SCN}; DM has no
 *       such column and the newest archived file's {@code NEXT_CHANGE#} is the ceiling;</li>
 *   <li>{@code ADD_LOGFILE}'s option means "add" (1) on DM where 2 means <em>remove</em>,
 *       so Oracle's NEW/ADDFILE constants cannot be reused;</li>
 *   <li>Oracle needs locale-independent NLS pinned before mining; DM has no such settings.</li>
 * </ul>
 */
public interface MiningDialect {

    /** Short name for logs and diagnostics. */
    String name();

    /**
     * Cheap capability probe: succeeds only when this source really speaks the dialect.
     * Used to pick a dialect in {@link MiningDialects}, never to guess from a product name.
     */
    void verify(Connection source) throws SQLException;

    /** Session settings the mining connection needs before anything else runs. */
    void prepareSession(Connection source) throws SQLException;

    /** Upper SCN bound of this mining session — where the next cycle resumes from. */
    long highWaterScn(Connection source) throws SQLException;

    /** Log files covering {@code (startScn, highWaterScn]}, in mining order. */
    List<String> logfilesCovering(Connection source, long startScn) throws SQLException;

    /** Issues ADD_LOGFILE for each path, then START_LOGMNR for the range. */
    void startMining(Connection source, List<String> logfiles, long startScn,
                     long highWaterScn) throws SQLException;

    /** Ends the mining session; failures are logged, never propagated. */
    void endMining(Connection source);

    /** The V$LOGMNR_CONTENTS query for this source. */
    String contentsQuery(Connection source) throws SQLException;

    /**
     * Whether an empty log list is a normal state to wait in rather than a
     * misconfiguration. False on Oracle (an ARCHIVELOG database always has files covering
     * the current SCN, so an empty list means purged logs or ARCHIVELOG off). True on DM,
     * which cannot mine anything until the current redo file is archived: right after
     * archiving is enabled there is nothing to mine and the cycle should just poll again.
     */
    boolean waitsForArchives();

    /**
     * Identity LogMiner deduplicates log files by (thread number + log sequence), not by
     * path, and rejects a repeat with ORA-01289. Shared by every dialect's discovery step.
     */
    static String threadSequence(int thread, long sequence) {
        return thread + ":" + sequence;
    }
}
