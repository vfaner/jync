package com.qqmu.jync.service.migration.es;

import java.util.List;

/** Result of one {@code _bulk} request. */
public record BulkResult(boolean hasErrors, List<FailedItem> failedItems) {

    public BulkResult {
        failedItems = failedItems == null ? List.of() : List.copyOf(failedItems);
    }

    public static BulkResult success() {
        return new BulkResult(false, List.of());
    }

    public record FailedItem(String id, String reason) {
    }
}
