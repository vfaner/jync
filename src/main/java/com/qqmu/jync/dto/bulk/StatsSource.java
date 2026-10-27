package com.qqmu.jync.dto.bulk;

/** Where a table's row count came from. */
public enum StatsSource {
    /** The product's own catalog/dictionary. */
    CATALOG,
    /** A {@code COUNT(*)} run because the user asked for exact numbers. */
    EXACT,
    /** Unknown: no catalog entry and, if requested, counting did not reach this table. */
    NONE
}
