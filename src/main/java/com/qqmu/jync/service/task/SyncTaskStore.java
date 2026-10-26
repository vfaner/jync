package com.qqmu.jync.service.task;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.SyncTaskRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Transactional access to the {@link SyncTask} row.
 *
 * <p>Separated from {@link SyncTaskRunner} because {@code @Transactional} is proxy-based:
 * calling these from inside the runner would bypass the proxy and lose the transaction.
 */
@Service
@Slf4j
public class SyncTaskStore {

    private final SyncTaskRepository taskRepository;
    private final SyncProperties properties;

    public SyncTaskStore(SyncTaskRepository taskRepository, SyncProperties properties) {
        this.taskRepository = taskRepository;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(Long projectId) {
        taskRepository.findByProjectId(projectId).ifPresent(task -> {
            task.setStatus(TaskStatus.RUNNING);
            taskRepository.save(task);
        });
    }

    /**
     * Stores a cycle's summary.
     *
     * <p>The status only flips to {@code ERROR} after {@code sync.max-retries} consecutive
     * failures, so one transient network blip does not make a healthy project look broken.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(Long projectId, SyncResult result) {
        taskRepository.findByProjectId(projectId).ifPresent(task -> {
            // "Last sync" answers "when did data last land", not "when did we last look": the
            // polling engine fires every second even on an idle source, so stamping every
            // outcome made the dashboard's timestamp tick while the row counter stood still.
            // A failed cycle moved nothing either — the previous successful time stays truthful.
            // The summary is still recorded below, so the list can still say "No changes".
            if (result.isSuccess() && result.hasChanges()) {
                task.setLastSyncTime(Instant.now());
            }
            task.setLastSyncResult(truncate(result.summary()));

            // A stop requested mid-cycle must survive the cycle's own outcome: the
            // scheduler entry is already gone, so flipping back to RUNNING (or to
            // ERROR, which promises "keeps retrying on schedule") would misreport
            // the project as active. Stats are still recorded either way.
            boolean stopped = task.getStatus() == TaskStatus.STOPPED;
            if (result.isSuccess()) {
                task.setConsecutiveFailures(0);
                if (!stopped) {
                    task.setStatus(TaskStatus.RUNNING);
                }
            } else {
                int failures = (task.getConsecutiveFailures() == null ? 0
                        : task.getConsecutiveFailures()) + 1;
                task.setConsecutiveFailures(failures);
                if (!stopped) {
                    task.setStatus(failures >= properties.getMaxRetries()
                            ? TaskStatus.ERROR : TaskStatus.RUNNING);
                }
                if (failures == properties.getMaxRetries()) {
                    log.error("Project {} has failed {} consecutive times; marking it ERROR. "
                            + "It keeps retrying on schedule.", projectId, failures);
                }
            }
            taskRepository.save(task);
        });
    }

    /**
     * Records a cycle the user stopped mid-run.
     *
     * <p>Not a failure: the failure ladder exists to park a project that keeps breaking, and
     * a deliberate stop would otherwise climb it (three stops during long full loads → ERROR,
     * which for CDC also parks the consumer). The status is STOPPED regardless of what the
     * cycle's own outcome would have set; 上次同步 still advances when rows did land, because
     * that timestamp answers "when did data last move".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStoppedOutcome(Long projectId, SyncResult result) {
        taskRepository.findByProjectId(projectId).ifPresent(task -> {
            if (result.hasChanges()) {
                task.setLastSyncTime(Instant.now());
            }
            task.setLastSyncResult(truncate("Stopped before finishing"));
            task.setStatus(TaskStatus.STOPPED);
            taskRepository.save(task);
        });
    }

    /** Creates the task row on demand so a project always has one to lock against. */
    @Transactional
    public SyncTask ensureTask(Project project) {
        return taskRepository.findByProjectId(project.getId()).orElseGet(() -> {
            SyncTask task = new SyncTask();
            task.setProjectId(project.getId());
            task.setStatus(TaskStatus.STOPPED);
            task.setConsecutiveFailures(0);
            return taskRepository.save(task);
        });
    }

    @Transactional
    public void markStopped(Long projectId) {
        taskRepository.findByProjectId(projectId).ifPresent(task -> {
            task.setStatus(TaskStatus.STOPPED);
            // The lease is deliberately NOT cleared here: a cycle may still be in
            // flight, and dropping its lock would let another instance acquire it and
            // run concurrently. The runner releases the lock when the cycle ends; a
            // crashed owner's lease clears on its own by TTL expiry.
            taskRepository.save(task);
        });
    }

    @Transactional(readOnly = true)
    public Optional<SyncTask> find(Long projectId) {
        return taskRepository.findByProjectId(projectId);
    }

    /** Tasks for the given projects, keyed by project id; projects without a row are absent. */
    @Transactional(readOnly = true)
    public Map<Long, SyncTask> findByProjectIds(Collection<Long> projectIds) {
        Map<Long, SyncTask> map = new LinkedHashMap<>();
        if (projectIds.isEmpty()) {
            return map;
        }
        for (SyncTask task : taskRepository.findByProjectIdIn(projectIds)) {
            map.put(task.getProjectId(), task);
        }
        return map;
    }

    @Transactional(readOnly = true)
    public List<SyncTask> findByStatus(TaskStatus status) {
        return taskRepository.findByStatus(status);
    }

    @Transactional
    public void deleteForProject(Long projectId) {
        taskRepository.deleteByProjectId(projectId);
    }

    /**
     * CDC fatal failure: ERROR immediately, without waiting for the max-retries
     * consecutive-failure ladder that polling cycles use. A purged binlog or an id
     * collision never heals by retrying; the UI must show it at once (task book B7).
     */
    @Transactional
    public void recordCdcFailure(Long projectId, String message) {
        taskRepository.findByProjectId(projectId).ifPresent(task -> {
            task.setStatus(TaskStatus.ERROR);
            task.setLastSyncResult(truncate(message));   // 复用既有私有工具 truncate(:140)
            task.setConsecutiveFailures(task.getConsecutiveFailures() == null
                    ? 1 : task.getConsecutiveFailures() + 1);
            taskRepository.save(task);
        });
    }

    /**
     * A CDC change window delivered rows: 上次同步 advances, same rule as polling.
     *
     * <p>The window logger only aggregates rows the applier already committed, so reaching
     * this call means data landed. Status and the result summary are deliberately untouched —
     * those belong to the consumer's own lifecycle ({@link #markCdcRunning},
     * {@link #recordCdcFailure}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCdcDataApplied(Long projectId) {
        taskRepository.findByProjectId(projectId).ifPresent(task -> {
            task.setLastSyncTime(Instant.now());
            taskRepository.save(task);
        });
    }

    /**
     * CDC consumer came up (or a rebaseline succeeded): RUNNING, ERROR hat off.
     *
     * <p>The stale result summary is cleared too: after a fatal failure it still carries the
     * old error text (e.g. {@code SERVER_ID_CONFLICT}), and leaving it there makes the
     * detail page show a failure forever while the panel above says RUNNING and data is
     * flowing. Cleared even when the status is already RUNNING, because a task can hold a
     * stale summary left behind by a recovery that predates this rule.
     */
    @Transactional
    public void markCdcRunning(Long projectId) {
        taskRepository.findByProjectId(projectId).ifPresent(task -> {
            boolean changed = false;
            if (task.getStatus() != TaskStatus.RUNNING) {
                task.setStatus(TaskStatus.RUNNING);
                changed = true;
            }
            if (task.getLastSyncResult() != null) {
                task.setLastSyncResult(null);
                changed = true;
            }
            if (changed) {
                taskRepository.save(task);
            }
        });
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "...";
    }
}
