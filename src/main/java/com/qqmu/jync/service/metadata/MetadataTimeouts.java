package com.qqmu.jync.service.metadata;

import java.sql.Statement;

import com.qqmu.jync.util.JdbcUtil;

/**
 * Holds the metadata-query timeout for readers.
 *
 * <p>Readers are partly constructed directly ({@code new XxxMetadataReader()}) rather than only
 * as Spring beans, so a static configured value is the pragmatic channel. Configured once at
 * startup from {@code sync.metadata-query-timeout-seconds}.
 */
public final class MetadataTimeouts {

    private static volatile int queryTimeoutSeconds = 60;

    private MetadataTimeouts() {
    }

    public static void configure(int timeoutSeconds) {
        queryTimeoutSeconds = timeoutSeconds;
    }

    /** Applies the configured metadata timeout to a freshly created statement. */
    public static void apply(Statement statement) {
        JdbcUtil.applyQueryTimeout(statement, queryTimeoutSeconds);
    }
}
