package com.qqmu.jync.dto;

/**
 * One table's exact row counts on both sides of a project.
 *
 * <p>Each side carries its own status: a missing target table or a slow source must not
 * hide the count that did succeed. {@code count} is null unless the status is OK.
 */
public record TableRowCount(
        String table,
        SideCount source,
        SideCount target,
        long elapsedMs) {

    public enum Status { OK, MISSING_TABLE, TIMEOUT, ERROR }

    public record SideCount(Status status, Long count, long elapsedMs) {
    }
}
