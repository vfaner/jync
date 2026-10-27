package com.qqmu.jync.dto.bulk;

import java.util.List;

/**
 * One sync task the wizard will create, with the tables binned into it.
 *
 * @param name         generated task name, e.g. {@code 同步任务-01}
 * @param estimatedRows sum of the known row counts in {@code tables}; unknown tables add 0
 * @param unknownCount tables in this bin whose row count is still unknown
 */
public record TaskPlan(String name,
                       List<TableEntry> tables,
                       long estimatedRows,
                       int unknownCount) {
}
