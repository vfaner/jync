package com.qqmu.jync.dto.bulk;

/**
 * The form behind the bulk task wizard: which two connections, how many tasks, what to name
 * them, and whether to pay for exact row counts.
 *
 * <p>The preview and the create call both take this record and both recompute the plan from
 * the source, so what the user sees is what gets written even though nothing is carried
 * between the two requests.
 *
 * @param exactStats run {@code COUNT(*)} on tables the catalog could not size; off by default
 */
public record BulkPlanRequest(Long sourceDbId,
                              Long targetDbId,
                              int taskCount,
                              String namePrefix,
                              String captureStrategy,
                              boolean exactStats) {
}
