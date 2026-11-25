package com.qqmu.jync.service.fanout;

import java.util.List;

import com.qqmu.jync.service.cdc.CdcTargetRepairService;

import lombok.extern.slf4j.Slf4j;

/**
 * Gives one fan-out target the same "target table was deleted" repair the single-target
 * consumers have: on a missing-table failure the target's table is recreated and reloaded
 * from the source, then the transaction is retried once.
 *
 * <p>Without this wrapper a missing table on an extra target only stops that worker; the
 * spool grows until a human recreates the table. The repair service is shared by all targets
 * and never throws — every outcome that is not REPAIRED falls back to the original failure,
 * so the worker keeps its usual stop-and-retry behaviour.
 */
@Slf4j
public final class RepairingEventApplier implements EventApplier {

    private final EventApplier delegate;
    private final CdcTargetRepairService repair;
    private final com.qqmu.jync.service.sync.SyncContext targetCtx;

    public RepairingEventApplier(EventApplier delegate, CdcTargetRepairService repair,
                                 com.qqmu.jync.service.sync.SyncContext targetCtx) {
        this.delegate = delegate;
        this.repair = repair;
        this.targetCtx = targetCtx;
    }

    @Override
    public void applyTransaction(String transactionId, String commitPosition,
                                 List<ChangeEvent> events) throws Exception {
        try {
            delegate.applyTransaction(transactionId, commitPosition, events);
            return;
        } catch (Exception e) {
            if (repair == null) {
                throw e;
            }
            CdcTargetRepairService.Outcome outcome = repair.repair(targetCtx, e);
            if (!outcome.repaired()) {
                if (outcome.status() != CdcTargetRepairService.Status.NOT_APPLICABLE) {
                    log.warn("Fan-out target repair for project {} did not succeed ({}): {}",
                            targetCtx.projectId(), outcome.status(), outcome.detail());
                }
                throw e;
            }
            log.info("Fan-out target repair for project {} recreated {}; retrying the"
                    + " transaction", targetCtx.projectId(), outcome.repairedTables());
            // The table returned by the repair service is the source's current structure and
            // a full reload just landed; the buffered transaction replays on top of it.
            delegate.applyTransaction(transactionId, commitPosition, events);
        }
    }

    @Override
    public void applyDdl(ChangeEvent event) throws Exception {
        delegate.applyDdl(event);
    }

    @Override
    public void close() throws Exception {
        delegate.close();
    }
}
