package com.qqmu.jync.service.task;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.ProjectRepository;

/**
 * The periodic convergence each node runs against the shared database.
 *
 * <p>Presence only, in both directions: enabled-but-not-scheduled-here gets scheduled,
 * scheduled-here-but-disabled gets removed, and anything already in step is left alone —
 * a reconciler that re-created matching jobs every tick would reset trigger timings and
 * starve short poll intervals.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleReconcilerTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private SyncScheduler scheduler;

    private ScheduleReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new ScheduleReconciler(projectRepository, scheduler);
    }

    private static Project project(long id, String name, boolean enabled) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setEnabled(enabled);
        return p;
    }

    @Test
    void aProjectStartedOnAnotherNodeGetsScheduledHere() {
        Project started = project(1L, "started-elsewhere", true);
        when(projectRepository.findAll()).thenReturn(List.of(started));
        when(scheduler.isScheduled(1L)).thenReturn(false);

        reconciler.reconcile();

        verify(scheduler).schedule(started);
        verify(scheduler, never()).unschedule(anyLong());
    }

    @Test
    void aProjectStoppedOnAnotherNodeGetsUnscheduledHere() {
        when(projectRepository.findAll()).thenReturn(List.of(project(2L, "stopped-elsewhere", false)));
        when(scheduler.isScheduled(2L)).thenReturn(true);

        reconciler.reconcile();

        verify(scheduler).unschedule(2L);
        verify(scheduler, never()).schedule(any());
    }

    @Test
    void schedulesAlreadyInStepAreLeftAlone() {
        when(projectRepository.findAll()).thenReturn(List.of(
                project(1L, "running", true),
                project(2L, "stopped", false)));
        when(scheduler.isScheduled(1L)).thenReturn(true);
        when(scheduler.isScheduled(2L)).thenReturn(false);

        reconciler.reconcile();

        verify(scheduler, never()).schedule(any());
        verify(scheduler, never()).unschedule(anyLong());
    }

    @Test
    void oneBrokenProjectDoesNotStopTheOthers() {
        Project bad = project(1L, "bad-cron", true);
        Project good = project(2L, "good", true);
        when(projectRepository.findAll()).thenReturn(List.of(bad, good));
        when(scheduler.isScheduled(1L)).thenReturn(false);
        when(scheduler.isScheduled(2L)).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("bad cron"))
                .when(scheduler).schedule(bad);

        reconciler.reconcile();

        verify(scheduler).schedule(good);
    }

    @Test
    void aDatabaseHiccupIsSurvivedAndTheTickSimplyRetriesLater() {
        when(projectRepository.findAll()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> reconciler.reconcile()).doesNotThrowAnyException();
        verify(scheduler, never()).schedule(any());
    }
}
