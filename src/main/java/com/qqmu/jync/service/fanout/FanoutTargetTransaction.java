package com.qqmu.jync.service.fanout;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.cdc.CdcTargetTransaction;

/**
 * A fan-out "target transaction" that writes nothing: it publishes the transaction's rows to
 * the shared {@link EventTransport} and lets one worker per target apply them.
 *
 * <p>This is what lets every JDBC capture engine serve a fan-out project without a second
 * stream implementation: the engines already funnel each source transaction through
 * {@link CdcTargetTransaction}, and this handle keeps that contract (buffer, then one commit
 * boundary). The engines' own source position advance is unchanged; the per-target positions
 * come from the commit event, whose token is supplied by the engine through
 * {@link CdcTargetTransaction#notePosition(String)}.
 */
public final class FanoutTargetTransaction implements CdcTargetTransaction {

    private final EventTransport transport;
    /** Used only when the engine supplied no position (tests / hand-built handles). */
    private final String fallbackTransactionId = UUID.randomUUID().toString();
    /** Pending rows are materialized at commit, after the position token is known. */
    private final List<Pending> buffer = new ArrayList<>();

    private record Pending(boolean delete, com.qqmu.jync.service.cdc.CdcTableBinding binding,
                           Map<String, Object> row) {
    }
    /** Set by the engine right before commit; the commit event carries it to every target. */
    private String position;
    private boolean closed;

    public FanoutTargetTransaction(EventTransport transport) {
        this.transport = transport;
    }

    @Override
    public void applyUpserts(CdcTableBinding binding, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            buffer.add(new Pending(false, binding, new LinkedHashMap<>(row)));
        }
    }

    @Override
    public void applyDeletes(CdcTableBinding binding, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            buffer.add(new Pending(true, binding, new LinkedHashMap<>(row)));
        }
    }

    @Override
    public void commit() throws SQLException {
        try {
            // Deterministic id: a publish that dies midway leaves the first rows in the
            // queue, the engine retries, and the replay must reuse the SAME id or the
            // worker sees "transaction X ended without a COMMIT" and never recovers.
            String transactionId = position == null || position.isBlank()
                    ? fallbackTransactionId : "tx:" + position;
            long now = System.currentTimeMillis();
            // Publish rows first, commit marker last: the marker is what makes a worker apply
            // the buffered transaction, so it must never be visible before its rows.
            for (Pending pending : buffer) {
                ChangeEvent event = pending.delete()
                        ? ChangeEvent.row(position, transactionId,
                                pending.binding().getDatabase(), pending.binding().getTable(),
                                ChangeEvent.ChangeOperation.DELETE, null, pending.row(), now)
                        : ChangeEvent.row(position, transactionId,
                                pending.binding().getDatabase(), pending.binding().getTable(),
                                ChangeEvent.ChangeOperation.UPSERT, pending.row(), null, now);
                transport.publish(event);
            }
            transport.publish(ChangeEvent.commit(position, transactionId, now));
            buffer.clear();
        } catch (Exception e) {
            // The transport's failures are IO failures; report them through the engines'
            // existing target-write channel (SQLException) so they retry/park the same way a
            // failing target write does.
            throw new SQLException("fan-out publish failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void notePosition(String token) {
        this.position = token;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // A transaction that never committed is discarded on the transport: only the commit
        // marker releases it to the workers, exactly like an aborted target transaction.
        buffer.clear();
    }
}
