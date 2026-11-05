package com.qqmu.jync.service.cdc;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * One CDC transaction's write handle on the target. The position may not advance until
 * {@link #commit()} has returned.
 *
 * <p>The seam exists so a cache target can be served without touching the five consumers'
 * stream logic: they apply the same upsert-then-delete order and commit once, and only the
 * handle behind this interface differs.
 *
 * <p>Failures are reported as {@link SQLException} (or unchecked exceptions), the channel
 * every consumer already funnels target-write failures through. Keeping the signature
 * narrower than the design's {@code Exception} leaves {@code YStreamCdcConsumer.handle}
 * and the binlog {@code TransactionSink} signatures untouched.
 */
public interface CdcTargetTransaction extends AutoCloseable {

    void applyUpserts(CdcTableBinding binding, List<Map<String, Object>> rows) throws SQLException;

    void applyDeletes(CdcTableBinding binding, List<Map<String, Object>> rows) throws SQLException;

    /**
     * JDBC: commits the target transaction. Redis: executes the buffered pipeline once and
     * checks every reply. Throwing means this transaction did not land — the caller must
     * not advance the position.
     */
    void commit() throws SQLException;

    /** Best-effort rollback on the failure path; always returns the resource it holds. */
    @Override
    void close();
}
