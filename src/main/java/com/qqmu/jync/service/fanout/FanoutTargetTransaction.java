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
    private final String transactionId = UUID.randomUUID().toString();
    private final List<ChangeEvent> buffer = new ArrayList<>();
    /** Set by the engine right before commit; the commit event carries it to every target. */
    private String position;
    private boolean closed;

    public FanoutTargetTransaction(EventTransport transport) {
        this.transport = transport;
    }

    @Override
    public void applyUpserts(CdcTableBinding binding, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            buffer.add(ChangeEvent.row(position, transactionId, binding.getDatabase(),
                    binding.getTable(), ChangeEvent.ChangeOperation.UPSERT,
                    new LinkedHashMap<>(row), null, System.currentTimeMillis()));
        }
    }

    @Override
    public void applyDeletes(CdcTableBinding binding, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            buffer.add(ChangeEvent.row(position, transactionId, binding.getDatabase(),
                    binding.getTable(), ChangeEvent.ChangeOperation.DELETE,
                    null, new LinkedHashMap<>(row), System.currentTimeMillis()));
        }
    }

    @Override
    public void commit() throws SQLException {
        try {
            // Publish rows first, commit marker last: the marker is what makes a worker apply
            // the buffered transaction, so it must never be visible before its rows.
            for (ChangeEvent event : buffer) {
                transport.publish(event);
            }
            transport.publish(ChangeEvent.commit(position, transactionId,
                    System.currentTimeMillis()));
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
