package com.qqmu.jync.service.migration;

import java.util.List;

/** What one migration run did, per index and in total. */
public record MigrationRunResult(
        boolean success,
        boolean cancelled,
        int indicesTotal,
        int indicesSucceeded,
        int indicesFailed,
        long documentsRead,
        long documentsWritten,
        String message,
        List<IndexResult> indices) {

    public MigrationRunResult {
        indices = indices == null ? List.of() : List.copyOf(indices);
    }

    public static MigrationRunResult empty() {
        return new MigrationRunResult(true, false, 0, 0, 0, 0, 0, null, List.of());
    }

    public record IndexResult(String sourceIndex, String targetIndex, Status status,
                              long documentsRead, long documentsWritten, String message) {
    }

    public enum Status {
        SUCCESS,
        FAILED,
        SKIPPED,
        CANCELLED
    }
}
