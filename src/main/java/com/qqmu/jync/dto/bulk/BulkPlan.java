package com.qqmu.jync.dto.bulk;

import java.util.List;

/**
 * The result of reading one source's table listing and splitting it into tasks.
 *
 * <p>The flags tell the page what it may claim: whether sizes came from the catalog,
 * whether exact counting ran (and whether it stopped at its budget before reaching every
 * unknown table), and whether the requested task count had to be cut down to the number of
 * tables.
 *
 * @param estimatedRows sum of the known row counts; unknown tables add nothing
 * @param unknownTables tables still without a row count after the requested statistics pass
 */
public record BulkPlan(String sourceName,
                       String targetName,
                       String schema,
                       List<TaskPlan> tasks,
                       int tableCount,
                       long estimatedRows,
                       int unknownTables,
                       boolean catalogStatsUsed,
                       boolean exactStatsUsed,
                       boolean exactStatsTruncated,
                       boolean taskCountClamped) {

    public int taskCount() {
        return tasks.size();
    }
}
