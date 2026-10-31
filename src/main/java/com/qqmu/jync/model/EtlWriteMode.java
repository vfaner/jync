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
    RANGE_OVERWRITE
}
