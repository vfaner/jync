package com.qqmu.jync.service.alert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.qqmu.jync.model.AlertCategory;
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

import lombok.extern.slf4j.Slf4j;

/**
 * The seven alert rules, each answered from state the application already keeps.
 *
 * <p>Read-only and additive: a signal source that throws costs its own category for that run,
 * never the others. Nothing here starts, stops or retries a sync — that remains the scheduler's
 * and the CDC supervisor's job.
 */
@Service
@Slf4j
public class DefaultAlertSignalCollector implements AlertSignalCollector {

    /** How recent a failure must be to describe a live fault. */
    static final Duration SYNC_FAILURE_WINDOW = Duration.ofMinutes(15);

    /** Failures of one table inside the window that make the table "failing", not blipping. */
    static final int SYNC_FAILURE_THRESHOLD = 3;

    /** A repair has just been logged; a minute of rows is enough to announce each one once. */
    static final Duration DRIFT_WINDOW = Duration.ofMinutes(5);

    /** Consecutive one-minute observations of a disconnected consumer before it is alerted. */
    static final int DISCONNECT_STREAK = 3;

    /** Consecutive failed probes before a connection is alerted; probes run every five minutes. */
    static final int HEALTH_FAILURE_STREAK = 3;

    static final Duration HEALTH_INTERVAL = Duration.ofMinutes(5);

    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final CdcConsumerManager cdcManager;
    private final DatabaseConfigService databaseConfigService;
    private final DatabaseHealthService healthService;
    private final ChangeLogRepository changeLogRepository;
    private final Clock clock;

    /**
     * Per-node streak counters. They are deliberately in memory: a node that restarts re-earns
     * the streak, which is cheaper than persisting a count that only gates noise.
     */
    private final Map<Long, Integer> disconnectStreak = new ConcurrentHashMap<>();
    private final Map<Long, Integer> healthFailureStreak = new ConcurrentHashMap<>();
    private final Set<Long> confirmedUnhealthy = ConcurrentHashMap.newKeySet();
    private final Map<Long, String> healthNames = new ConcurrentHashMap<>();
    private final Map<Long, String> healthMessages = new ConcurrentHashMap<>();
    private Instant lastHealthCheckAt;

    @Autowired
    public DefaultAlertSignalCollector(ProjectRepository projectRepository,
                                       ProjectService projectService,
                                       CdcConsumerManager cdcManager,
                                       DatabaseConfigService databaseConfigService,
                                       DatabaseHealthService healthService,
                                       ChangeLogRepository changeLogRepository) {
        this(projectRepository, projectService, cdcManager, databaseConfigService,
                healthService, changeLogRepository, Clock.systemUTC());
    }

    DefaultAlertSignalCollector(ProjectRepository projectRepository,
                                ProjectService projectService,
                                CdcConsumerManager cdcManager,
                                DatabaseConfigService databaseConfigService,
                                DatabaseHealthService healthService,
                                ChangeLogRepository changeLogRepository,
                                Clock clock) {
        this.projectRepository = projectRepository;
        this.projectService = projectService;
        this.cdcManager = cdcManager;
        this.databaseConfigService = databaseConfigService;
        this.healthService = healthService;
        this.changeLogRepository = changeLogRepository;
        this.clock = clock;
    }

    @Override
    public List<AlertEvent> collect() {
        Instant now = clock.instant();
        List<AlertEvent> events = new ArrayList<>();
        collectSyncFailures(now, events);
        collectProjectConditions(now, events);
        collectDrift(now, events);
        collectConnectionHealth(now, events);
        return events;
    }

    // ── 同步失败 ────────────────────────────────────────────────────────────────

    private void collectSyncFailures(Instant now, List<AlertEvent> events) {
        List<ChangeLog> rows;
        try {
            rows = changeLogRepository
                    .findBySuccessFalseAndOccurredAtAfter(now.minus(SYNC_FAILURE_WINDOW));
        } catch (RuntimeException e) {
            log.debug("Cannot read recent failures for alerting: {}", e.toString());
            return;
        }
        Map<String, FailureGroup> groups = new LinkedHashMap<>();
        for (ChangeLog row : rows) {
            if (!StringUtils.hasText(row.getObjectName())) {
                continue;
            }
            // A PROJECT-level error is the cycle as a whole; a table-level alert for it would
            // point the reader at a table that may be perfectly fine.
            if (row.getObjectType() == ObjectType.PROJECT) {
                continue;
            }
            String key = row.getProjectId() + "|"
                    + row.getObjectName().toUpperCase(Locale.ROOT);
            groups.computeIfAbsent(key,
                            ignored -> new FailureGroup(row.getProjectId(), row.getObjectName()))
                    .count++;
        }
        Map<Long, String> names = projectNames(groupsProjectIds(groups));
        for (FailureGroup group : groups.values()) {
            if (group.count < SYNC_FAILURE_THRESHOLD) {
                continue;
            }
            events.add(new AlertEvent(AlertCategory.SYNC_FAILURE, group.projectId,
                    names.get(group.projectId), group.objectName,
                    "alert.detail.syncFailure",
                    new Object[]{group.count, SYNC_FAILURE_WINDOW.toMinutes()}, now));
        }
    }

    private static final class FailureGroup {
        private final long projectId;
        private final String objectName;
        private int count;

        private FailureGroup(long projectId, String objectName) {
            this.projectId = projectId;
            this.objectName = objectName;
        }
    }

    private static Set<Long> groupsProjectIds(Map<String, FailureGroup> groups) {
        Set<Long> ids = new LinkedHashSet<>();
        for (FailureGroup group : groups.values()) {
            ids.add(group.projectId);
        }
        return ids;
    }

    // ── 项目状态 / CDC 停摆 / PG slot ──────────────────────────────────────────

    private void collectProjectConditions(Instant now, List<AlertEvent> events) {
        List<Project> projects;
        try {
            projects = projectRepository.findByEnabledTrue();
        } catch (RuntimeException e) {
            log.debug("Cannot list enabled projects for alerting: {}", e.toString());
            return;
        }
        Map<Long, SyncTask> tasks;
        try {
            List<Long> ids = new ArrayList<>();
            for (Project project : projects) {
                ids.add(project.getId());
            }
            tasks = projectService.findTasks(ids);
            if (tasks == null) {
                tasks = Map.of();
            }
        } catch (RuntimeException e) {
            log.debug("Cannot read sync tasks for alerting: {}", e.toString());
            tasks = Map.of();
        }

        for (Project project : projects) {
            try {
                collectOneProject(project, tasks.get(project.getId()), now, events);
            } catch (RuntimeException e) {
                // A single unreadable project must not cost every other project's alerts.
                log.debug("Cannot evaluate project {} for alerting: {}",
                        project.getId(), e.toString());
            }
        }
    }

    private void collectOneProject(Project project, SyncTask task, Instant now,
                                   List<AlertEvent> events) {
        Long id = project.getId();
        CaptureStrategy strategy = cdcManager.strategyOf(project);
        if (strategy == null) {
            strategy = CaptureStrategy.POLLING;
        }
        if (!strategy.isLogCdc()) {
            ProjectService.CycleView cycle = projectService.activeCycle(id);
            if (cycle != null && cycle.stuck()) {
                events.add(new AlertEvent(AlertCategory.POLL_STUCK, id, project.getName(), "",
                        "alert.detail.pollStuck",
                        new Object[]{cycle.minutesSinceProgress(),
                                cycle.stuckThresholdMinutes()}, now));
            }
            if (!projectService.isScheduled(id)) {
                events.add(new AlertEvent(AlertCategory.PROJECT_NOT_RUNNING, id,
                        project.getName(), "", "alert.detail.projectNotRunning",
                        new Object[0], now));
            }
            disconnectStreak.remove(id);
            return;
        }

        if (task != null && task.getStatus() == TaskStatus.ERROR) {
            events.add(new AlertEvent(AlertCategory.CDC_STOPPED, id, project.getName(), "",
                    "alert.detail.cdcParked",
                    new Object[]{task.getLastSyncResult() == null
                            ? "" : task.getLastSyncResult()}, now));
        }

        CdcRuntimeStats stats = cdcManager.statsFor(id);
        if (stats == null) {
            // Not consumed on this node: it may be running elsewhere, so absence proves
            // nothing. The parked check above is the per-project verdict.
            disconnectStreak.remove(id);
            return;
        }
        boolean disconnected = !stats.isConnected() && stats.getLastError() != null;
        int streak = disconnected
                ? disconnectStreak.merge(id, 1, Integer::sum)
                : reset(disconnectStreak, id);
        if (streak >= DISCONNECT_STREAK) {
            events.add(new AlertEvent(AlertCategory.CDC_STOPPED, id, project.getName(), "",
                    "alert.detail.cdcDisconnected", new Object[]{stats.getLastError()}, now));
        }
        if (stats.isRetainedWalHigh() && stats.getRetainedWalBytes() != null) {
            events.add(new AlertEvent(AlertCategory.PG_SLOT_BACKLOG, id, project.getName(), "",
                    "alert.detail.pgSlotBacklog",
                    new Object[]{stats.getRetainedWalLabel(),
                            stats.getRetainedWalBytes()}, now));
        }
    }

    private static int reset(Map<Long, Integer> streak, Long id) {
        streak.remove(id);
        return 0;
    }

    // ── 数据漂移 ────────────────────────────────────────────────────────────────

    private void collectDrift(Instant now, List<AlertEvent> events) {
        List<ChangeLog> rows;
        try {
            rows = changeLogRepository
                    .findByChangeTypeAndOccurredAtAfter(ChangeType.INFO,
                            now.minus(DRIFT_WINDOW));
        } catch (RuntimeException e) {
            log.debug("Cannot read row-count repairs for alerting: {}", e.toString());
            return;
        }
        // One repair can be reported once; several rows for one table inside the window must
        // collapse to the newest, or the same drift would be announced repeatedly.
        Map<String, ChangeLog> newest = new LinkedHashMap<>();
        for (ChangeLog row : rows) {
            if (!isRowCountAudit(row) && !isContentAudit(row)) {
                continue;
            }
            String key = row.getProjectId() + "|" + row.getObjectName();
            ChangeLog previous = newest.get(key);
            if (previous == null || row.getOccurredAt().isAfter(previous.getOccurredAt())) {
                newest.put(key, row);
            }
        }
        Map<Long, String> names = projectNames(projectIdsOf(newest.values()));
        for (ChangeLog row : newest.values()) {
            if (isContentAudit(row)) {
                Long differing = contentDriftRowsOf(row);
                events.add(new AlertEvent(AlertCategory.DATA_DRIFT, row.getProjectId(),
                        names.get(row.getProjectId()), row.getObjectName(),
                        "alert.detail.contentDrift",
                        new Object[]{row.getObjectName(),
                                differing == null ? 0L : differing},
                        row.getOccurredAt()));
                continue;
            }
            Long surplus = surplusOf(row);
            if (surplus == null) {
                events.add(new AlertEvent(AlertCategory.DATA_DRIFT, row.getProjectId(),
                        names.get(row.getProjectId()), row.getObjectName(),
                        "alert.detail.dataDrift",
                        new Object[]{row.getObjectName()}, row.getOccurredAt()));
            } else {
                // Nothing was repaired: the target is holding rows the source deleted, which
                // polling will never remove on its own. Saying "automatically repaired" here
                // would describe an action that did not happen.
                events.add(new AlertEvent(AlertCategory.DATA_DRIFT, row.getProjectId(),
                        names.get(row.getProjectId()), row.getObjectName(),
                        "alert.detail.dataSurplus",
                        new Object[]{row.getObjectName(), surplus}, row.getOccurredAt()));
            }
        }
    }

    /** "Row count audit: target has 3 row(s) more than the source (…)": the surplus size. */
    private static final Pattern ROW_COUNT_SURPLUS = Pattern.compile(
            "Row count audit: target has (\\d+) row\\(s\\) more than the source");

    /**
     * "Content audit: table ORDERS: 50 sampled, 3 row(s) differ (columns: …)": the number of
     * sampled rows whose values differ. The audit never repairs, so the count is the whole
     * message.
     */
    private static final Pattern CONTENT_DRIFT = Pattern.compile(
            "^Content audit: table .*: (\\d+) sampled, (\\d+) row\\(s\\) differ");

    private static boolean isContentAudit(ChangeLog row) {
        return row.getObjectType() == ObjectType.TABLE
                && row.getDetails() != null
                && row.getDetails().startsWith("Content audit: table ");
    }

    private static Long contentDriftRowsOf(ChangeLog row) {
        Matcher m = CONTENT_DRIFT.matcher(row.getDetails());
        return m.find() ? Long.valueOf(m.group(2)) : null;
    }

    /**
     * Whether a change-log row was written by the row-count audit.
     *
     * <p>An audit reaches the log two ways: as an entry of its own when the reload wrote
     * nothing, or as the cause appended after a reload's load counts. Only the first starts
     * with the audit's name, so the marker is matched anywhere in the details.
     */
    private static boolean isRowCountAudit(ChangeLog row) {
        return row.getObjectType() == ObjectType.DATA
                && row.getDetails() != null
                && row.getDetails().toLowerCase(Locale.ROOT).contains("row count audit");
    }

    /** The surplus an audit entry reports, or {@code null} when it reports a repair. */
    private static Long surplusOf(ChangeLog row) {
        Matcher m = ROW_COUNT_SURPLUS.matcher(row.getDetails());
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }

    private static Set<Long> projectIdsOf(Iterable<ChangeLog> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ChangeLog row : rows) {
            ids.add(row.getProjectId());
        }
        return ids;
    }

    // ── 连接健康 ────────────────────────────────────────────────────────────────

    private void collectConnectionHealth(Instant now, List<AlertEvent> events) {
        boolean due = lastHealthCheckAt == null
                || !now.isBefore(lastHealthCheckAt.plus(HEALTH_INTERVAL));
        if (due) {
            lastHealthCheckAt = now;
            try {
                probeConnections();
            } catch (RuntimeException e) {
                log.debug("Connection health probe for alerting failed: {}", e.toString());
            }
        }
        for (Long id : new LinkedHashSet<>(confirmedUnhealthy)) {
            // The connection id is the object key: with an empty object every broken connection
            // collapses onto "DB_UNHEALTHY:0:", so all but one alert is dropped and a recovery of
            // the last one clears a still-broken connection.
            events.add(new AlertEvent(AlertCategory.DB_UNHEALTHY, 0L,
                    healthNames.getOrDefault(id, "#" + id), "#" + id,
                    "alert.detail.dbUnhealthy",
                    new Object[]{healthMessages.getOrDefault(id, "")}, now));
        }
    }

    private void probeConnections() {
        List<DatabaseConfig> configs = databaseConfigService.findAll();
        if (configs == null || configs.isEmpty()) {
            confirmedUnhealthy.clear();
            return;
        }
        Set<Long> currentIds = new LinkedHashSet<>();
        for (DatabaseConfig config : configs) {
            currentIds.add(config.getId());
            healthNames.put(config.getId(), config.getName());
        }
        confirmedUnhealthy.retainAll(currentIds);
        healthNames.keySet().retainAll(currentIds);
        healthMessages.keySet().retainAll(currentIds);
        healthFailureStreak.keySet().retainAll(currentIds);

        List<DatabaseHealthService.Health> healths = healthService.check(configs, true);
        if (healths == null) {
            return;
        }
        for (DatabaseHealthService.Health health : healths) {
            if (health == null) {
                continue;
            }
            if (health.ok()) {
                healthFailureStreak.remove(health.id());
                confirmedUnhealthy.remove(health.id());
                healthMessages.remove(health.id());
                continue;
            }
            int streak = healthFailureStreak.merge(health.id(), 1, Integer::sum);
            healthMessages.put(health.id(), health.message());
            if (streak >= HEALTH_FAILURE_STREAK) {
                confirmedUnhealthy.add(health.id());
            }
        }
    }

    // ── shared ─────────────────────────────────────────────────────────────────

    private Map<Long, String> projectNames(Set<Long> ids) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        try {
            for (Project project : projectRepository.findAllById(ids)) {
                names.put(project.getId(), project.getName());
            }
        } catch (RuntimeException e) {
            log.debug("Cannot resolve project names for alerting: {}", e.toString());
        }
        for (Long id : ids) {
            names.putIfAbsent(id, "#" + id);
        }
        return names;
    }
}
