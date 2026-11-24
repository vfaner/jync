package com.qqmu.jync.service.ai.support;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.cdc.CdcNotHolderException;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcRestartFailedException;
import com.qqmu.jync.service.etl.EtlTaskService;
import com.qqmu.jync.service.migration.MigrationTaskService;
import com.qqmu.jync.service.settings.SystemTuningService;

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
    private final DatabaseConfigService databases;
    private final SystemTuningService tuning;
    private final AdminAuditService audit;

    public SupportActionService(SupportActionStore store, ProjectService projects,
                                EtlTaskService etlTasks, MigrationTaskService migrationTasks,
                                CdcOperationsService cdcOperations, DatabaseConfigService databases,
                                SystemTuningService tuning, AdminAuditService audit) {
        this.store = store;
        this.projects = projects;
        this.etlTasks = etlTasks;
        this.migrationTasks = migrationTasks;
        this.cdcOperations = cdcOperations;
        this.databases = databases;
        this.tuning = tuning;
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
            // A config save is not visible on the page until a refresh, and it only takes
            // effect on the next start; saying "done" alone invites "so it is faster now?".
            return new Result(Outcome.OK, doneKey(pending.kind(), pending.action()));
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
            case CONNECTION -> runConnection(pending, actor);
            case SYSTEM -> runSystem(pending, actor);
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
            // The payload holds only whitelisted tuning fields; updateTunables re-checks
            // ownership, the stopped state and the ranges against the fresh row.
            case UPDATE_CONFIG ->
                    projects.updateTunables(pending.resourceId(), pending.payload(), actor);
            case UPDATE_CURSOR -> projects.saveCursorSettings(pending.resourceId(),
                    stringMap(pending.payload().get("columns")),
                    stringList(pending.payload().get("priority")), actor);
            case UPDATE_SCHEDULE -> projects.updateSchedule(pending.resourceId(),
                    string(pending.payload().get("cronExpression")), actor);
            default -> throw new IllegalStateException("support.action.failed");
        }
    }

    private void runConnection(SupportActionStore.Pending pending, AccessGuard actor) {
        switch (pending.action()) {
            case UPDATE_PARAMS -> databases.updateExtraParams(pending.resourceId(),
                    stringMap(pending.payload().get("set")),
                    stringList(pending.payload().get("remove")), actor);
            default -> throw new IllegalStateException("support.action.failed");
        }
    }

    private void runSystem(SupportActionStore.Pending pending, AccessGuard actor) {
        switch (pending.action()) {
            case UPDATE_PARAMS -> {
                // The service re-checks this too; a card prepared before a role change must
                // still fail at the click.
                if (!actor.isAdmin()) {
                    throw new AccessDeniedException(AccessGuard.DENIED_KEY);
                }
                tuning.apply(stringMap(pending.payload().get("set")),
                        stringList(pending.payload().get("reset")), actor);
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

    private String doneKey(SupportActionStore.Kind kind, SupportActionStore.Action action) {
        return switch (action) {
            case UPDATE_CONFIG -> "support.action.config.done";
            case UPDATE_CURSOR -> "support.action.cursor.done";
            case UPDATE_SCHEDULE -> "support.action.schedule.done";
            case UPDATE_PARAMS -> kind == SupportActionStore.Kind.SYSTEM
                    ? "support.action.system.done" : "support.action.connection.done";
            default -> "support.action.done";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("error.support.param.invalid");
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("error.support.param.invalid");
            }
            out.put(key, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("error.support.param.invalid");
        }
        List<String> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException("error.support.param.invalid");
            }
            out.add(text);
        }
        return out;
    }

    private String string(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("error.support.param.invalid");
        }
        return text;
    }

    private void audit(SupportActionStore.Pending pending, AccessGuard actor, boolean success) {
        String outcome = "@msg:" + (success ? "audit.support.outcome.ok"
                : "audit.support.outcome.failed");
        if (pending.kind() == SupportActionStore.Kind.SYSTEM) {
            audit.record(AuditAction.AI_SUPPORT_ACTION, AuditDetail.of(
                            "audit.detail.supportSystem", changes(pending.payload()), outcome),
                    actor.username());
            return;
        }
        if (pending.action() == SupportActionStore.Action.UPDATE_CONFIG
                || pending.action() == SupportActionStore.Action.UPDATE_CURSOR
                || pending.action() == SupportActionStore.Action.UPDATE_SCHEDULE
                || pending.action() == SupportActionStore.Action.UPDATE_PARAMS) {
            // A generic "action done" row cannot answer "what changed?". Field names and
            // tuning values are not credentials, so the audit carries them.
            audit.record(AuditAction.AI_SUPPORT_ACTION, AuditDetail.of(
                            "audit.detail.supportConfig",
                            pending.resourceName(), changes(pending.payload()), outcome),
                    actor.username());
            return;
        }
        audit.record(AuditAction.AI_SUPPORT_ACTION, AuditDetail.of("audit.detail.supportAction",
                        "@msg:" + SupportActionStore.titleKey(pending.kind(), pending.action()),
                        pending.resourceName(), outcome),
                actor.username());
    }

    private String changes(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : new TreeMap<>(payload).entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            // audit_event.detail is 1024 chars; a long driver-parameter value must not turn a
            // successful save into a failed audit insert.
            if (sb.length() > 480) {
                return sb.substring(0, 480) + "…";
            }
        }
        return sb.toString();
    }
}
