package com.qqmu.jync.service.ai.support;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.cdc.CdcNotHolderException;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcRestartFailedException;
import com.qqmu.jync.service.etl.EtlTaskService;
import com.qqmu.jync.service.migration.MigrationTaskService;

import lombok.extern.slf4j.Slf4j;

/**
 * Redeems an assistant-prepared action after the human clicked its card.
 *
 * <p>The card is a hand-off, never an authority: the token only says <em>which</em> action was
 * discussed. Whether it may run is decided here by calling the same service methods the
 * buttons call, which re-apply {@link AccessGuard#requireEditable} against the current owner.
 * A card prepared before an ownership change therefore fails at the click, not after it.
 *
 * <p>The audit row records the action, the resource and the outcome — never SQL, connection
 * strings or row data.
 */
@Service
@Slf4j
public class SupportActionService {

    public enum Outcome {
        OK, EXPIRED, DENIED, FAILED
    }

    public record Result(Outcome outcome, String messageKey) {
        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    private final SupportActionStore store;
    private final ProjectService projects;
    private final EtlTaskService etlTasks;
    private final MigrationTaskService migrationTasks;
    private final CdcOperationsService cdcOperations;
    private final AdminAuditService audit;

    public SupportActionService(SupportActionStore store, ProjectService projects,
                                EtlTaskService etlTasks, MigrationTaskService migrationTasks,
                                CdcOperationsService cdcOperations, AdminAuditService audit) {
        this.store = store;
        this.projects = projects;
        this.etlTasks = etlTasks;
        this.migrationTasks = migrationTasks;
        this.cdcOperations = cdcOperations;
        this.audit = audit;
    }

    public Result execute(String token, AccessGuard actor) {
        SupportActionStore.Pending pending =
                actor == null ? null : store.take(token, actor.username()).orElse(null);
        if (pending == null) {
            return new Result(Outcome.EXPIRED, "support.action.expired");
        }
        if (!actor.canWrite()) {
            audit(pending, actor, false);
            return new Result(Outcome.DENIED, "error.accessDenied");
        }
        try {
            run(pending, actor);
            audit(pending, actor, true);
            return new Result(Outcome.OK, "support.action.done");
        } catch (AccessDeniedException e) {
            // Ownership was checked here as well as when the card was prepared, but the row
            // may have changed hands between the two.
            log.info("Support action '{}' on {} #{} refused for {}",
                    pending.action(), pending.kind(), pending.resourceId(), actor.username());
            audit(pending, actor, false);
            return new Result(Outcome.DENIED, AccessGuard.DENIED_KEY);
        } catch (CdcNotHolderException e) {
            // A rebaseline must run on the node that holds the consumer lease. Nothing was
            // mutated; the key tells the operator to act on the holder instead.
            log.info("Support action '{}' on project #{} refused: {}",
                    pending.action(), pending.resourceId(), e.getMessage());
            audit(pending, actor, false);
            return new Result(Outcome.FAILED, e.getMessage());
        } catch (CdcRestartFailedException e) {
            // The reset committed and the restart did not. Supervision retries, so this is a
            // reportable failure with a fixed key, not a generic one.
            log.info("Support action '{}' on project #{} reset but failed to restart",
                    pending.action(), pending.resourceId());
            audit(pending, actor, false);
            return new Result(Outcome.FAILED, CdcRestartFailedException.MESSAGE_KEY);
        } catch (IllegalArgumentException | IllegalStateException e) {
            String key = e.getMessage();
            // Only i18n keys leave the service: a driver's raw SQL error stays in the log.
            String messageKey = key != null && key.startsWith("error.")
                    ? key : "support.action.failed";
            log.info("Support action '{}' on {} #{} failed: {}",
                    pending.action(), pending.kind(), pending.resourceId(), key);
            audit(pending, actor, false);
            return new Result(Outcome.FAILED, messageKey);
        }
    }

    private void run(SupportActionStore.Pending pending, AccessGuard actor) {
        switch (pending.kind()) {
            case PROJECT -> runProject(pending, actor);
            case ETL_TASK -> runEtlTask(pending, actor);
            case MIGRATION_TASK -> runMigrationTask(pending, actor);
        }
    }

    private void runProject(SupportActionStore.Pending pending, AccessGuard actor) {
        switch (pending.action()) {
            case START -> projects.start(pending.resourceId(), actor);
            case STOP -> projects.stop(pending.resourceId(), actor);
            case SYNC_NOW -> projects.syncNow(pending.resourceId(), actor);
            // rebaseline() takes no actor: it checks the CDC strategy and the cross-node lease
            // itself and writes its own audit trail, so ownership must be re-applied here —
            // exactly the step CdcApiController performs before it calls the same method.
            case REBASELINE -> {
                projects.requireEditable(pending.resourceId(), actor);
                cdcOperations.rebaseline(pending.resourceId());
            }
            default -> throw new IllegalStateException("support.action.failed");
        }
    }

    private void runEtlTask(SupportActionStore.Pending pending, AccessGuard actor) {
        switch (pending.action()) {
            case START -> etlTasks.setEnabled(pending.resourceId(), true, actor);
            case STOP -> etlTasks.setEnabled(pending.resourceId(), false, actor);
            case RUN_NOW -> {
                // submit() has no actor of its own; qualify before anything is queued.
                etlTasks.requireEditable(pending.resourceId(), actor);
                if (!etlTasks.submit(pending.resourceId())) {
                    throw new IllegalStateException("error.etl.running");
                }
            }
            default -> throw new IllegalStateException("support.action.failed");
        }
    }

    private void runMigrationTask(SupportActionStore.Pending pending, AccessGuard actor) {
        switch (pending.action()) {
            case START -> migrationTasks.setEnabled(pending.resourceId(), true, actor);
            case STOP -> migrationTasks.setEnabled(pending.resourceId(), false, actor);
            case RUN_NOW -> {
                // submit() has no actor of its own; qualify before a RUNNING row is written.
                migrationTasks.requireEditable(pending.resourceId(), actor);
                if (!migrationTasks.submit(pending.resourceId(), MigrationRunTrigger.MANUAL)) {
                    throw new IllegalStateException("error.migration.running");
                }
            }
            case CANCEL -> {
                // cancel() already re-checks ownership; the boolean says whether a run was
                // actually in flight, which is the difference between done and a no-op.
                if (!migrationTasks.cancel(pending.resourceId(), actor)) {
                    throw new IllegalStateException("error.migration.not.running");
                }
            }
            default -> throw new IllegalStateException("support.action.failed");
        }
    }

    private void audit(SupportActionStore.Pending pending, AccessGuard actor, boolean success) {
        audit.record(AuditAction.AI_SUPPORT_ACTION, AuditDetail.of("audit.detail.supportAction",
                        "@msg:" + SupportActionStore.titleKey(pending.kind(), pending.action()),
                        pending.resourceName(),
                        "@msg:" + (success ? "audit.support.outcome.ok"
                                : "audit.support.outcome.failed")),
                actor.username());
    }
}
