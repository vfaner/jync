package com.qqmu.jync.service.migration.mongo;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.qqmu.jync.service.migration.MigrationGlob;
import com.qqmu.jync.service.migration.MigrationRunResult;
import com.qqmu.jync.service.migration.MigrationRunResult.IndexResult;
import com.qqmu.jync.service.migration.MigrationRunResult.Status;

/**
 * The parts the two MongoDB migration engines share: collection selection, the run result
 * shape and the small failure/cleanup helpers. Keeping them here means a fix lands in both
 * directions at once.
 */
final class MongoMigrationSupport {

    private MongoMigrationSupport() {
    }

    /** Collections matching the patterns, minus MongoDB's internal {@code system.*} ones. */
    static List<MongoCollectionInfo> matching(List<MongoCollectionInfo> discovered,
                                              String collectionsText) {
        List<MongoCollectionInfo> collections = new ArrayList<>();
        for (MongoCollectionInfo info : discovered) {
            if (!MigrationGlob.matchesAny(info.name(), collectionsText)) {
                continue;
            }
            if (MongoCollectionSupport.isSystem(info.name())
                    && !MigrationGlob.namesPrefix(collectionsText, "system.")) {
                continue;
            }
            collections.add(info);
        }
        return collections;
    }

    /**
     * The run's outcome. Copying nothing is a failure, not a no-op: the usual cause is a
     * mistyped pattern, and reporting SUCCESS 0/0 would claim the migration worked.
     */
    static MigrationRunResult outcome(List<IndexResult> results, List<String> runWarnings,
                                      boolean cancelled) {
        long documentsRead = results.stream().mapToLong(IndexResult::documentsRead).sum();
        long documentsWritten = results.stream().mapToLong(IndexResult::documentsWritten).sum();
        int succeeded = (int) results.stream()
                .filter(result -> result.status() == Status.SUCCESS).count();
        int failed = (int) results.stream()
                .filter(result -> result.status() == Status.FAILED).count();

        List<String> messages = new ArrayList<>(runWarnings);
        for (IndexResult result : results) {
            if (result.message() != null && !result.message().isBlank()) {
                messages.add(result.sourceIndex() + ": " + result.message());
            }
        }
        return new MigrationRunResult(!cancelled && failed == 0 && succeeded > 0,
                cancelled, results.size(), succeeded, failed, documentsRead, documentsWritten,
                messages.isEmpty() ? null : String.join("; ", messages), results);
    }

    static MigrationRunResult failedRun(String message) {
        return new MigrationRunResult(false, false, 0, 0, 0, 0, 0, message, List.of());
    }

    static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static String sqlMessage(SQLException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing a failed connection must not hide the run result.
        }
    }

    /** Cumulative counters for one collection, kept so a cancelled run still reports them. */
    static final class Progress {
        long read;
        long written;
    }
}
