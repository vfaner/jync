package com.qqmu.jync.service.etl;

import java.sql.SQLException;
import java.util.List;

/**
 * A forward-only stream of extraction rows, already converted to JDBC values.
 *
 * <p>{@link #columns()} and {@link #resumeKeyIndex()} are available before the first
 * {@link #next()} — the runner needs them to create the target table and to look up the resume
 * key before anything on the target is deleted.
 */
public interface EtlSourceCursor extends AutoCloseable {

    List<EtlSourceColumn> columns();

    /** 1-based index of the resume key column, or {@code -1} when the run cannot resume. */
    int resumeKeyIndex();

    boolean next() throws SQLException;

    Object getObject(int oneBasedIndex) throws SQLException;

    String getString(int oneBasedIndex) throws SQLException;

    @Override
    void close() throws SQLException;
}
