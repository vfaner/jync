package com.qqmu.jync.service.etl;

import java.sql.SQLException;

import com.qqmu.jync.model.EtlTask;

/**
 * One read-only extraction source. JDBC databases implement it over a {@code Connection};
 * MongoDB implements it over the native driver.
 *
 * <p>An opened source owns its underlying connection or client and must be closed by the
 * caller. {@link EtlSourceException} marks a failure that belongs to the source side;
 * {@link SQLException} keeps the runner's existing stage-based attribution.
 */
public interface EtlSource extends AutoCloseable {

    EtlSourceCursor query(EtlTask task, EtlCheckpointValue checkpoint) throws SQLException;

    EtlSourceProbe probe(EtlTask task, int timeoutSeconds) throws SQLException;

    @Override
    void close();
}
