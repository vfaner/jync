package com.qqmu.jync.service.cdc;

import java.time.Instant;
import java.util.Collection;

import org.springframework.stereotype.Service;

import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.repository.ChangeLogRepository;

/**
 * Closes the open DDL tasks of tables that Jync itself rebuilt from the source definition.
 *
 * <p>When a target table disappears, {@code CdcTargetRepairService} recreates it with the same
 * DDL generator structure sync uses — the source's current columns, keys and indexes — and then
 * reloads it in full. Both steps a skipped-DDL task asks for (align the target, replay the gap
 * rows) have therefore been done by the system. Leaving the task open would ask the
 * administrator to run an ALTER whose column now exists, keep the column projection forced on
 * after every consumer restart, and keep the row out of the retention pruner forever.
 *
 * <p>Only tasks whose statement names one of the rebuilt tables are closed; another table's
 * task stays pending. A statement the parser cannot name, or a {@code RENAME} (which names the
 * old table), is left to the administrator rather than closed on a guess.
 *
 * <p>Dependency-light on purpose: {@code CdcDdlTaskService} already depends on
 * {@code CdcConsumerManager}, which depends on the repair service that calls this class.
 * Injecting the task service here would close that loop.
 */
@Service
public class CdcDdlTaskReconciler {

    /** Who closed a task the system resolved itself, as shown in the change log. */
    public static final String REBUILD_HANDLED_BY = "system (cdc target repair)";

    private final ChangeLogRepository repository;

    public CdcDdlTaskReconciler(ChangeLogRepository repository) {
        this.repository = repository;
    }

    /**
     * @param sourceTables the source tables the repair cycle recreated and reloaded
     * @return how many tasks were closed
     */
    public int resolveAfterTargetRebuild(Long projectId, Collection<String> sourceTables) {
        if (projectId == null || sourceTables == null || sourceTables.isEmpty()) {
            return 0;
        }
        int closed = 0;
        Instant now = Instant.now();
        for (ChangeLog open : repository.findOpenDdlTasks(projectId)) {
            String table = CdcDdlTaskService.alteredTable(
                    CdcDdlTaskService.stripPrefix(open.getDetails()));
            if (table == null || !containsIgnoreCase(sourceTables, table)) {
                continue;
            }
            open.setDdlExecuted(true);
            open.setReplayDone(true);
            open.setResolutionStatus(ChangeLog.RESOLUTION_PROCESSED);
            open.setErrorDetail(null);
            open.setHandledBy(REBUILD_HANDLED_BY);
            open.setHandledAt(now);
            repository.save(open);
            closed++;
        }
        return closed;
    }

    private static boolean containsIgnoreCase(Collection<String> values, String wanted) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }
}
