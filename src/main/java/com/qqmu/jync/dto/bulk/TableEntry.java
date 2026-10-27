package com.qqmu.jync.dto.bulk;

/**
 * One table in the plan.
 *
 * @param rows   estimated row count; null when neither the catalog nor exact counting supplied one
 * @param bytes  estimated size in bytes; null when the product has no byte estimate
 * @param source where {@code rows} came from
 */
public record TableEntry(String name, Long rows, Long bytes, StatsSource source) {
}
