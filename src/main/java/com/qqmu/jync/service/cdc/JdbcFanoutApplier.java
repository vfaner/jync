package com.qqmu.jync.service.cdc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qqmu.jync.service.fanout.CdcBindingResolver;
import com.qqmu.jync.service.fanout.ChangeEvent;
import com.qqmu.jync.service.fanout.EventApplier;
import com.qqmu.jync.service.sync.SyncContext;

import lombok.extern.slf4j.Slf4j;

/**
 * One fan-out target's write side: groups a source transaction's row events by table binding,
 * applies upserts before deletes, and commits once against this target's connection.
 *
 * <p>Each target owns its connection and transaction; the capture never writes a target
 * directly. A failure throws out of {@link #applyTransaction}, so the worker stops this
 * target without acknowledging the transaction (at-least-once on retry).
 */
@Slf4j
public final class JdbcFanoutApplier implements EventApplier {

    private final SyncContext targetCtx;
    private final CdcBindingResolver bindings;
    private final CdcEventApplier applier;

    public JdbcFanoutApplier(SyncContext targetCtx, CdcRuntimeStats stats,
                             CdcBindingResolver bindings) {
        this.targetCtx = targetCtx;
        this.bindings = bindings;
        this.applier = new CdcEventApplier(targetCtx, stats, 0);
    }

    @Override
    public void applyTransaction(String transactionId, String commitPosition,
                                 List<ChangeEvent> events) throws Exception {
        Map<CdcTableBinding, List<Map<String, Object>>> upserts = new LinkedHashMap<>();
        Map<CdcTableBinding, List<Map<String, Object>>> deletes = new LinkedHashMap<>();
        for (ChangeEvent event : events) {
            CdcTableBinding binding = bindings.resolve(event.schema(), event.table());
            if (binding == null || !binding.isApplicable()) {
                log.debug("Fan-out target skips {}.{}: no applicable binding",
                        event.schema(), event.table());
                continue;
            }
            Map<String, Object> row = event.operation() == ChangeEvent.ChangeOperation.DELETE
                    && event.oldRow() != null ? event.oldRow() : event.row();
            if (row == null) {
                continue;
            }
            if (event.operation() == ChangeEvent.ChangeOperation.DELETE) {
                deletes.computeIfAbsent(binding, b -> new ArrayList<>()).add(row);
            } else {
                upserts.computeIfAbsent(binding, b -> new ArrayList<>()).add(row);
            }
        }
        if (upserts.isEmpty() && deletes.isEmpty()) {
            return;
        }
        Connection conn = targetCtx.getConnectionProvider().open(targetCtx.getTargetConfig());
        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e : upserts.entrySet()) {
                applier.applyUpserts(conn, e.getKey(), e.getValue());
            }
            for (Map.Entry<CdcTableBinding, List<Map<String, Object>>> e : deletes.entrySet()) {
                applier.applyDeletes(conn, e.getKey(), e.getValue());
            }
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            rollback(conn);
            throw e;
        } finally {
            try {
                conn.setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                log.debug("Fan-out target autocommit restore failed: {}", e.toString());
            }
            close(conn);
        }
    }

    @Override
    public void applyDdl(ChangeEvent event) {
        // Schema changes are applied by the capture-side structure sync / per-table repair;
        // a target worker must not execute arbitrary source DDL on its own.
        log.debug("Fan-out target received DDL for {}.{}: {}", event.schema(), event.table(),
                event.ddlSql());
    }

    private static void rollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.debug("Fan-out target rollback failed: {}", e.toString());
        }
    }

    private static void close(Connection conn) {
        try {
            conn.close();
        } catch (SQLException e) {
            log.debug("Fan-out target connection close failed: {}", e.toString());
        }
    }
}
