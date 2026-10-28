package com.qqmu.jync.service.cdc;

import java.time.Duration;

import com.qqmu.jync.model.DatabaseConfig;

/**
 * Opens a throwaway replication stream and decodes one event from it.
 *
 * <p>This is the capability test for MySQL-wire products that are not native MySQL. Their
 * SQL dialect is MySQL's, but the binlog wire format is not guaranteed to be: MariaDB
 * forked it in 10.5, TiDB removed its binlog in 8.0, and the domestic products each ship
 * their own implementation behind a MySQL-compatible front end. A banner/version check
 * cannot tell those apart, and the JDBC surface says nothing about the replication stream
 * — only executing the client's decoder against a real stream can.
 *
 * <p>The probe reads from the start of the current binlog file (position 4) and stops as
 * soon as one event is decoded, so it never replays the whole file. It opens no
 * transaction and writes nothing.
 */
@FunctionalInterface
public interface BinlogEventReader {

    /**
     * @param source     the connection whose stream is opened
     * @param binlogFile the binlog file to read from
     * @param position   the position to start at (4 = the file's FormatDescription event)
     * @param serverId   the fake replica id this probe presents
     * @param timeout    how long to wait for the connection and the first event
     * @return a short description of the first decoded event, never null
     * @throws Exception carrying the server's or the decoder's own message
     */
    String readFirstEvent(DatabaseConfig source, String binlogFile, long position,
                          long serverId, Duration timeout) throws Exception;
}
