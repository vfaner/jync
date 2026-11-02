package com.qqmu.jync.service.migration.connection;

/** What a connection card shows after a successful probe. */
public record MigrationConnectionTestResult(boolean success, String message, String product,
                                            String version, String clusterName, long elapsedMs) {
}
