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
            task.setLastSyncTime(Instant.now());
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

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000) + "...";
    }
}
