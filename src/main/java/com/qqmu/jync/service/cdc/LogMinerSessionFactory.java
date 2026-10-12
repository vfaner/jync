package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens one LogMiner mining session on demand (v1.6.0).
 *
 * <p>A session is the ADD_LOGFILE + START_LOGMNR + cursor bundle for a contiguous redo range
 * starting at {@code startScn}. The consumer opens a fresh session per drain cycle: archived
 * logs age out, online redo switches, and a session whose logfiles vanished mid-mine raises
 * ORA-01291 (PURGED) which the consumer classifies fatally.
 */
@FunctionalInterface
public interface LogMinerSessionFactory {

    /**
     * Mines every change with {@code CSCN > startScn} visible to the source connection.
     * The caller owns {@code source} and closes it with the session (the session closes
     * END_LOGMNR quietly; the connection itself is returned to the pool by the consumer).
     */
    LogMinerSession open(Connection source, long startScn, int batchSize) throws SQLException;
}
