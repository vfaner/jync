package com.qqmu.jync.service.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.repository.MigrationTaskRepository;

@ExtendWith(MockitoExtension.class)
class MigrationSchedulerTest {

    @Mock private MigrationTaskRepository taskRepository;
    @Mock private MigrationTaskService taskService;

    private MigrationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MigrationScheduler(taskRepository, taskService);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    private static MigrationTask task(long id, boolean enabled, String cron) {
        MigrationTask task = new MigrationTask();
        task.setId(id);
        task.setName("task-" + id);
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        task.setEnabled(enabled);
        task.setCronExpression(cron);
        return task;
    }

    @Test
    void startupSchedulesOnlyEnabledTasksWithACron() {
        MigrationTask scheduled = task(1L, true, "0 */5 * * * *");
        MigrationTask disabled = task(2L, false, "0 */5 * * * *");
        MigrationTask cronless = task(3L, true, " ");
        when(taskRepository.findByEnabledTrueAndType(MigrationType.ES_TO_EASYSEARCH))
                .thenReturn(List.of(scheduled, cronless));
        when(taskRepository.findById(1L)).thenReturn(Optional.of(scheduled));
        when(taskRepository.findById(3L)).thenReturn(Optional.of(cronless));

        scheduler.scheduleAllOnStartup();

        assertThat(scheduler.isScheduled(1L)).isTrue();
        assertThat(scheduler.isScheduled(3L)).isFalse();
        verify(taskRepository, never()).findById(disabled.getId());
    }

    @Test
    void refreshReplacesTheRegisteredTrigger() {
        MigrationTask task = task(1L, true, "0 */5 * * * *");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        scheduler.refresh(1L);
        ScheduledFuture<?> first = scheduler.scheduledFuture(1L);
        scheduler.refresh(1L);
        ScheduledFuture<?> second = scheduler.scheduledFuture(1L);

        assertThat((Object) first).isNotSameAs(second);
        assertThat(first.isCancelled()).isTrue();
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
    }

    @Test
    void refreshRemovesTheTriggerWhenTheTaskIsGone() {
        MigrationTask task = task(1L, true, "0 */5 * * * *");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        scheduler.refresh(1L);

        when(taskRepository.findById(1L)).thenReturn(Optional.empty());
        scheduler.refresh(1L);

        assertThat(scheduler.isScheduled(1L)).isFalse();
    }

    @Test
    void deletingATaskCancelsItsTrigger() {
        MigrationTask task = task(1L, true, "0 */5 * * * *");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        scheduler.refresh(1L);
        ScheduledFuture<?> future = scheduler.scheduledFuture(1L);

        scheduler.onTaskChanged(new MigrationTaskChangedEvent(1L, true));

        assertThat(future.isCancelled()).isTrue();
        assertThat(scheduler.isScheduled(1L)).isFalse();
    }

    @Test
    void savingATaskConvergesItsTrigger() {
        MigrationTask task = task(1L, true, "0 */5 * * * *");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        scheduler.onTaskChanged(new MigrationTaskChangedEvent(1L, false));

        assertThat(scheduler.isScheduled(1L)).isTrue();
    }

    @Test
    void aFiringSubmitsTheTaskWithTheScheduleTrigger() {
        when(taskService.submit(1L, MigrationRunTrigger.SCHEDULE)).thenReturn(true);

        scheduler.trigger(1L);

        verify(taskService).submit(1L, MigrationRunTrigger.SCHEDULE);
    }

    @Test
    void aSkippedFiringIsNotAnError() {
        when(taskService.submit(1L, MigrationRunTrigger.SCHEDULE)).thenReturn(false);

        scheduler.trigger(1L);

        verify(taskService).submit(1L, MigrationRunTrigger.SCHEDULE);
    }

    @Test
    void aFailingSubmissionIsSwallowedSoTheTriggerKeepsFiring() {
        when(taskService.submit(1L, MigrationRunTrigger.SCHEDULE))
                .thenThrow(new IllegalStateException("boom"));

        scheduler.trigger(1L);

        verify(taskService).submit(any(), any());
    }

    @Test
    void anInvalidStoredCronDoesNotBreakScheduling() {
        MigrationTask task = task(1L, true, "not a cron");
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        scheduler.refresh(1L);

        assertThat(scheduler.isScheduled(1L)).isFalse();
    }
}
