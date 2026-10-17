package com.qqmu.jync.dto;

import java.util.List;

/**
 * The first rows of one table, shaped for the read-only preview modal.
 *
 * <p>Every cell is a string (or null for SQL NULL): the browser only displays them, and
 * letting Jackson serialize raw JDBC types would turn a TIMESTAMP into an epoch number
 * and a BLOB into an unbounded array.
 */
public record TablePreview(
        String side,
        String connection,
        String schema,
        String table,
        List<String> columns,
        List<List<String>> rows,
        boolean truncated,
        long elapsedMs) {
}
