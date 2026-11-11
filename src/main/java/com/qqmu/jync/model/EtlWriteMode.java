package com.qqmu.jync.model;

/**
 * How an ETL run leaves the rows that were already in the target table.
 *
 * <p>ETL does not try to keep the target identical to the source: the customer owns the
 * extraction SQL and the target table. These modes only describe what Jync may delete.
 */
public enum EtlWriteMode {

    /**
     * Delete every target row, then copy the query result. A run that fails part way is
     * repaired by running it again from zero; no checkpoint is kept.
     */
    OVERWRITE,

    /**
     * Insert without deleting. With a resume key the next run continues after the last
     * committed key instead of starting over.
     */
    APPEND,

    /**
     * Delete only the rows matching the configured range condition, then copy the query
     * result. With a resume key, a continuation run skips the delete and resumes the copy.
     */
    RANGE_OVERWRITE,

    /**
     * Never delete. A result row whose configured key columns already exist on the target
     * updates that row; every other row is inserted. This is the mode for refreshing a table
     * from a query that already knows the natural key.
     */
    UPSERT;

    /**
     * Whether a resume key applies to this mode. Only the two modes that copy the result
     * without matching existing target rows read it: OVERWRITE starts from zero, and UPSERT
     * re-applies every row by key, so honoring a stored checkpoint there would skip exactly
     * the rows whose updates the mode exists to apply.
     */
    public boolean supportsResume() {
        return this == APPEND || this == RANGE_OVERWRITE;
    }
}
