package com.qqmu.jync.service.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTask;
import com.qqmu.jync.model.TaskStatus;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcRuntimeStats;
import com.qqmu.jync.service.connection.DatabaseHealthService;

/**
 * Each alert category is answered from state the application already keeps — change-log rows,
 * the polling lock's progress, the CDC consumer's counters, the health probe's verdict.
 *
 * <p>The collector is read-only and additive: a source that throws costs its own category,
 * never the rest of the run.
 */
@ExtendWith(MockitoExtension.class)
class DefaultAlertSignalCollectorTest {

    private static final Instant T0 = Instant.parse("2026-10-31T10:00:00Z");

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectService projectService;
    @Mock private CdcConsumerManager cdcManager;
    @Mock private DatabaseConfigService databaseConfigService;
    @Mock private DatabaseHealthService healthService;
    @Mock private ChangeLogRepository changeLogRepository;

    private MutableClock clock;
    private DefaultAlertSignalCollector collector;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        collector = new DefaultAlertSignalCollector(projectRepository, projectService,
                cdcManager, databaseConfigService, healthService, changeLogRepository, clock);
        lenient().when(projectRepository.findByEnabledTrue()).thenReturn(new ArrayList<>());
        lenient().when(projectService.findTasks(any())).thenReturn(Map.of());
        lenient().when(projectRepository.findAllById(any())).thenReturn(new ArrayList<>());
        lenient().when(databaseConfigService.findAll()).thenReturn(new ArrayList<>());
        lenient().when(healthService.check(anyList(), anyBoolean())).thenReturn(new ArrayList<>());
    }

    private static Project project(long id, String name) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        project.setEnabled(true);
        return project;
    }

    private static ChangeLog failure(long projectId, ObjectType type, String object,
                                     ChangeType changeType, Instant at) {
        ChangeLog row = new ChangeLog();
        row.setProjectId(projectId);
        row.setObjectType(type);
        row.setObjectName(object);
        row.setChangeType(changeType);
        row.setSuccess(changeType != ChangeType.ERROR);
        row.setOccurredAt(at);
        return row;
    }

    private static SyncTask task(long projectId, TaskStatus status, String result) {
        SyncTask task = new SyncTask();
        task.setProjectId(projectId);
        task.setStatus(status);
        task.setLastSyncResult(result);
        return task;
    }

    private List<AlertEvent> collect() {
        return collector.collect();
    }

    private static AlertEvent only(List<AlertEvent> events, com.qqmu.jync.model.AlertCategory
            category) {
        List<AlertEvent> matching = new ArrayList<>();
        for (AlertEvent event : events) {
            if (event.category() == category) {
                matching.add(event);
            }
        }
        assertThat(matching).as(category.name()).hasSize(1);
        return matching.get(0);
    }

    @Test
    void threeFailuresOfTheSameTableBecomeOneSyncFailureEvent() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, "orders")));
        when(projectRepository.findAllById(any())).thenReturn(List.of(project(1L, "orders")));
        when(changeLogRepository.findBySuccessFalseAndOccurredAtAfter(any())).thenReturn(List.of(
                failure(1L, ObjectType.DATA, "ORDERS", ChangeType.ERROR, T0.minusSeconds(60)),
                failure(1L, ObjectType.DATA, "ORDERS", ChangeType.ERROR, T0.minusSeconds(30)),
                failure(1L, ObjectType.TABLE, "orders", ChangeType.ERROR, T0.minusSeconds(10)),
                // A second table with too few failures must stay quiet.
                failure(1L, ObjectType.DATA, "ITEMS", ChangeType.ERROR, T0.minusSeconds(5))));

        AlertEvent event = only(collect(), com.qqmu.jync.model.AlertCategory.SYNC_FAILURE);

        // Case-insensitive: the log may spell the table the way the driver reported it.
        assertThat(event.object()).isEqualTo("ORDERS");
        assertThat(event.subject()).isEqualTo("orders");
        assertThat(event.detailKey()).isEqualTo("alert.detail.syncFailure");
        assertThat(event.detailArgs()[0]).isEqualTo(3);
    }

    @Test
    void cycleLevelErrorsAreNotTableFailures() {
        when(changeLogRepository.findBySuccessFalseAndOccurredAtAfter(any())).thenReturn(List.of(
                failure(1L, ObjectType.PROJECT, "orders", ChangeType.ERROR, T0.minusSeconds(60)),
                failure(1L, ObjectType.PROJECT, "orders", ChangeType.ERROR, T0.minusSeconds(30)),
                failure(1L, ObjectType.PROJECT, "orders", ChangeType.ERROR, T0.minusSeconds(10))));

        assertThat(collect()).noneMatch(
                e -> e.category() == com.qqmu.jync.model.AlertCategory.SYNC_FAILURE);
    }

    @Test
    void aStuckPollingCycleIsReported() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, "orders")));
        when(cdcManager.strategyOf(any())).thenReturn(CaptureStrategy.POLLING);
        when(projectService.isScheduled(1L)).thenReturn(true);
        when(projectService.activeCycle(1L)).thenReturn(new ProjectService.CycleView(
                true, null, null, 35, true, 30));

        AlertEvent event = only(collect(), com.qqmu.jync.model.AlertCategory.POLL_STUCK);

        assertThat(event.detailKey()).isEqualTo("alert.detail.pollStuck");
        assertThat(event.detailArgs()).containsExactly(35L, 30L);
    }

    @Test
    void anEnabledPollingProjectWithoutAScheduleIsReported() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, "orders")));
        when(cdcManager.strategyOf(any())).thenReturn(CaptureStrategy.POLLING);
        when(projectService.isScheduled(1L)).thenReturn(false);

        AlertEvent event = only(collect(),
                com.qqmu.jync.model.AlertCategory.PROJECT_NOT_RUNNING);

        assertThat(event.detailKey()).isEqualTo("alert.detail.projectNotRunning");
    }

    @Test
    void aParkedCdcProjectIsReportedInsteadOfAMissingSchedule() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, "orders")));
        when(cdcManager.strategyOf(any())).thenReturn(CaptureStrategy.LOGMINER_CDC);
        when(projectService.findTasks(any())).thenReturn(
                Map.of(1L, task(1L, TaskStatus.ERROR, "README: fatal stream failure")));

        List<AlertEvent> events = collect();

        AlertEvent event = only(events, com.qqmu.jync.model.AlertCategory.CDC_STOPPED);
        assertThat(event.detailKey()).isEqualTo("alert.detail.cdcParked");
        assertThat(String.valueOf(event.detailArgs()[0])).contains("fatal stream failure");
        // A CDC project is expected to have no polling schedule; that is not "not running".
        assertThat(events).noneMatch(
                e -> e.category() == com.qqmu.jync.model.AlertCategory.PROJECT_NOT_RUNNING);
    }

    @Test
    void aTransientCdcDisconnectNeedsThreeObservations() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, "orders")));
        when(cdcManager.strategyOf(any())).thenReturn(CaptureStrategy.PG_LOGICAL_CDC);
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.setConnected(false);
        stats.setLastError("connection reset");
        when(cdcManager.statsFor(1L)).thenReturn(stats);

        assertThat(collect()).noneMatch(
                e -> e.category() == com.qqmu.jync.model.AlertCategory.CDC_STOPPED);
        clock.advance(Duration.ofMinutes(1));
        assertThat(collect()).noneMatch(
                e -> e.category() == com.qqmu.jync.model.AlertCategory.CDC_STOPPED);
        clock.advance(Duration.ofMinutes(1));

        AlertEvent event = only(collect(), com.qqmu.jync.model.AlertCategory.CDC_STOPPED);
        assertThat(event.detailKey()).isEqualTo("alert.detail.cdcDisconnected");
    }

    @Test
    void aHighPgSlotBacklogIsReported() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, "orders")));
        when(cdcManager.strategyOf(any())).thenReturn(CaptureStrategy.PG_LOGICAL_CDC);
        CdcRuntimeStats stats = new CdcRuntimeStats();
        stats.setConnected(true);
        stats.recordRetainedWal(5L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024);
        when(cdcManager.statsFor(1L)).thenReturn(stats);

        AlertEvent event = only(collect(),
                com.qqmu.jync.model.AlertCategory.PG_SLOT_BACKLOG);

        assertThat(event.detailKey()).isEqualTo("alert.detail.pgSlotBacklog");
        assertThat(event.detailArgs()[0]).isEqualTo(stats.getRetainedWalLabel());
    }

    @Test
    void aRowCountAuditEntryBecomesADataDriftEvent() {
        when(projectRepository.findAllById(any())).thenReturn(List.of(project(1L, "orders")));
        ChangeLog drift = failure(1L, ObjectType.DATA, "ORDERS", ChangeType.INFO,
                T0.minusSeconds(60));
        drift.setDetails("Row count audit: target is missing 4 row(s) already marked as synced");
        ChangeLog unrelated = failure(1L, ObjectType.DATA, "ITEMS", ChangeType.INFO,
                T0.minusSeconds(30));
        unrelated.setDetails("Loaded 12 row(s)");
        when(changeLogRepository.findByChangeTypeAndOccurredAtAfter(any(), any()))
                .thenReturn(List.of(drift, unrelated));

        AlertEvent event = only(collect(), com.qqmu.jync.model.AlertCategory.DATA_DRIFT);

        assertThat(event.object()).isEqualTo("ORDERS");
        assertThat(event.occurredAt()).isEqualTo(drift.getOccurredAt());
        assertThat(event.detailKey()).isEqualTo("alert.detail.dataDrift");
    }

    @Test
    void threeFailedHealthChecksAreNeededBeforeAConnectionIsReported() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(7L);
        config.setName("prod");
        when(databaseConfigService.findAll()).thenReturn(List.of(config));
        when(healthService.check(anyList(), eq(true))).thenReturn(List.of(
                new DatabaseHealthService.Health(7L, false, 12, "refused")));

        assertThat(collect()).noneMatch(
                e -> e.category() == com.qqmu.jync.model.AlertCategory.DB_UNHEALTHY);
        clock.advance(Duration.ofMinutes(5));
        assertThat(collect()).noneMatch(
                e -> e.category() == com.qqmu.jync.model.AlertCategory.DB_UNHEALTHY);
        clock.advance(Duration.ofMinutes(5));

        AlertEvent event = only(collect(),
                com.qqmu.jync.model.AlertCategory.DB_UNHEALTHY);
        assertThat(event.subject()).isEqualTo("prod");
        assertThat(event.projectId()).isZero();

        when(healthService.check(anyList(), eq(true))).thenReturn(List.of(
                new DatabaseHealthService.Health(7L, true, 3, null)));
        clock.advance(Duration.ofMinutes(5));
        assertThat(collect()).noneMatch(
                e -> e.category() == com.qqmu.jync.model.AlertCategory.DB_UNHEALTHY);
    }

    @Test
    void oneBrokenSignalSourceDoesNotHideTheOthers() {
        when(projectRepository.findByEnabledTrue()).thenReturn(List.of(project(1L, "orders")));
        when(cdcManager.strategyOf(any())).thenThrow(new IllegalStateException("bad config"));
        when(changeLogRepository.findBySuccessFalseAndOccurredAtAfter(any())).thenReturn(List.of(
                failure(1L, ObjectType.DATA, "ORDERS", ChangeType.ERROR, T0.minusSeconds(60)),
                failure(1L, ObjectType.DATA, "ORDERS", ChangeType.ERROR, T0.minusSeconds(30)),
                failure(1L, ObjectType.DATA, "ORDERS", ChangeType.ERROR, T0.minusSeconds(10))));

        only(collect(), com.qqmu.jync.model.AlertCategory.SYNC_FAILURE);
    }

    @Test
    void aFreshApplicationHealthCheckDoesNotDialOnEveryEvaluation() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(7L);
        config.setName("prod");
        when(databaseConfigService.findAll()).thenReturn(List.of(config));

        collect();
        clock.advance(Duration.ofMinutes(1));
        collect();

        // Two evaluations inside the five-minute probe interval dial once.
        org.mockito.Mockito.verify(healthService, org.mockito.Mockito.times(1))
                .check(anyList(), eq(true));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
