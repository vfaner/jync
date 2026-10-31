package com.qqmu.jync.service.etl;

import java.util.List;

/**
 * What a source can say about an extraction without returning rows.
 *
 * @param columns        the writable result columns, already filtered to plain identifiers
 * @param resumeKeyIndex 1-based column of the resume key, or {@code -1} when it is absent or
 *                       not configured; the precheck reports the mismatch as an issue
 * @param issues         source-side checks, in the order they should be shown
 */
public record EtlSourceProbe(
        List<EtlSourceColumn> columns, int resumeKeyIndex, List<EtlSourceIssue> issues) {

    public EtlSourceProbe {
        columns = List.copyOf(columns);
        issues = List.copyOf(issues);
    }
}
