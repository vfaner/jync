package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncTaskRepository;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;

/**
 * A cycle that lost its lease must not write the task row.
 *
 * <p>The lease was taken over, so the row now belongs to the new owner's cycle. Recording the
 * abandoned attempt here climbed the failure ladder and — at {@code max-retries} — flipped the
 * task to ERROR, which for a CDC project is the hat that parks the consumer. A machine that
 * merely rebounced (restart, second node, an expired lease) then looked permanently broken and
 * never restarted replication.
 */
class SyncTaskRunnerLockLostTest {

    private ProjectRepository projectRepository;
    private SyncTaskRepository taskRepository;
    private SyncContextFactory contextFactory;
    private SyncEngine syncEngine;
    private SyncTaskStore taskStore;
    private SyncTaskRunner runner;
    private SyncTask task;
    private SyncResult engineResult;

    @BeforeEach
    void setUp() {
        projectRepository = mock(ProjectRepository.class);
        taskRepository = mock(SyncTaskRepository.class);
        contextFactory = mock(SyncContextFactory.class);
        syncEngine = mock(SyncEngine.class);
        taskStore = new SyncTaskStore(taskRepository, new SyncProperties());
        SyncLockStore lockStore = mock(SyncLockStore.class);
        runner = new SyncTaskRunner(projectRepository, contextFactory, syncEngine,
                new SyncLockService(lockStore, new SyncProperties(), 8080), taskStore,
                new PoolActivity(new SyncProperties()));

        when(lockStore.acquire(eq(1L), anyString(), anyLong())).thenReturn(true);
        when(lockStore.renew(eq(1L), anyString(), anyLong())).thenReturn(true);
        Project project = new Project();
        project.setId(1L);
        project.setName("p");
        project.setEnabled(true);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        DatabaseConfig source = new DatabaseConfig();
        source.setId(1L);
        DatabaseConfig target = new DatabaseConfig();
        target.setId(2L);
        when(contextFactory.build(any(), anyString(), any(), any())).thenReturn(
                SyncContext.builder().project(project).config(new SyncConfig())
                        .sourceConfig(source).targetConfig(target).build());

        // Last failure of the ladder: one more recorded failure would flip the task to ERROR.
        task = new SyncTask();
        task.setProjectId(1L);
        task.setStatus(TaskStatus.RUNNING);
        task.setConsecutiveFailures(new SyncProperties().getMaxRetries() - 1);
        when(taskRepository.findByProjectId(1L)).thenReturn(Optional.of(task));

        engineResult = new SyncResult();
        engineResult.addError("Sync lock lost mid-run (lease expired or reclaimed)");
        engineResult.setLockLost(true);
        when(syncEngine.runCycle(any())).thenReturn(engineResult);
    }

    @AfterEach
    void tearDown() {
        runner.shutdownRerunExecutor();
    }

    @Test
    void aLostLeaseLeavesTheTaskRowToTheNewOwner() {
        SyncTaskRunner.Outcome outcome = runner.runOnce(1L);

        assertThat(outcome.result()).isPresent();
        assertThat(outcome.result().orElseThrow().isSuccess()).isFalse();
        // Nothing recorded: the failure ladder stands where it was and the ERROR hat stays off.
        assertThat(task.getConsecutiveFailures())
                .isEqualTo(new SyncProperties().getMaxRetries() - 1);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(task.getLastSyncResult()).isNull();
    }
}
