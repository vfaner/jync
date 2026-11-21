package com.qqmu.jync.service.cdc;

import com.qqmu.jync.service.sync.SyncContext;

/**
 * The one place the five CDC engines decide what to do about a missing target table.
 *
 * <p>Keeping the decision here — rather than repeating it in each run loop — is what makes the
 * behaviour identical across MySQL binlog, Oracle/DM LogMiner, PostgreSQL logical, SQL Server
 * CDC and Yashan YStream: either the targeted repair rebuilt the table (the caller continues
 * replaying from the same position) or it did not, and the caller parks with the reason the
 * repair produced.
 */
final class CdcTargetRepairGate {

    private CdcTargetRepairGate() {
    }

    /**
     * @return {@code null} when the failure is not a missing-target-table fault; otherwise the
     *     repair outcome. {@link CdcTargetRepairService.Outcome#repaired()} tells the caller
     *     whether it may continue replaying.
     */
    static CdcTargetRepairService.Outcome attempt(CdcTargetRepairService service,
                                                  SyncContext ctx, Throwable failure,
                                                  CdcRuntimeStats stats) {
        if (failure == null || CdcFailureClassifier.classify(failure)
                != CdcFailureClassifier.Kind.TARGET_TABLE_MISSING) {
            return null;
        }
        if (service == null) {
            // Tests and hand-built consumers may not wire the repair service. Parking is the
            // safe default: an absent table never heals by reconnecting.
            return new CdcTargetRepairService.Outcome(
                    CdcTargetRepairService.Status.NOT_APPLICABLE, java.util.List.of(),
                    "targeted repair is not wired on this engine");
        }
        CdcTargetRepairService.Outcome outcome = service.repair(ctx, failure);
        if (outcome.repaired() && stats != null) {
            // The rebuilt table carries the source's current structure; the consumer's
            // cached target-column set predates it and would project the new columns away
            // for up to its TTL. Drop it before the replayed transaction is applied.
            stats.invalidateTargetColumns();
        }
        return outcome;
    }
}
