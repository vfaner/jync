package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.projection.redis.RedisValueFormat;
import com.qqmu.jync.repository.AlertStateRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;
import com.qqmu.jync.service.projection.redis.RedisProjectionPolicy;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * The target-kind field is new on an existing table. These tests pin the two things that can
 * break an existing installation: a legacy row (kind null) must compare equal to the JDBC form
 * it was loaded into, and switching to Redis must reset the sync state the same way a JDBC
 * endpoint change already does.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTargetKindTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private DatabaseConfigRepository databaseConfigRepository;
    @Mock private SyncProgressRepository progressRepository;
    @Mock private ChangeLogRepository changeLogRepository;
    @Mock private ChunkCheckpointRepository checkpointRepository;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private MetadataReaderFactory readerFactory;
    @Mock private MetadataSnapshotService snapshotService;
    @Mock private SyncContextFactory contextFactory;
    @Mock private SyncScheduler scheduler;
    @Mock private SyncTaskRunner taskRunner;
    @Mock private SyncTaskStore taskStore;
    @Mock private SyncEngine syncEngine;
    @Mock private SyncLockService lockService;
    @Mock private AdminAuditService auditService;
    @Mock private CdcConsumerManager cdcManager;
    @Mock private SyncStateWriter stateWriter;
    @Mock private CdcPositionStore positionStore;
    @Mock private TaskMetricSampleRepository taskMetricSampleRepository;
    @Mock private AlertStateRepository alertStateRepository;
    @Mock private RedisProjectionPolicy redisPolicy;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, checkpointRepository, dataSourceManager,
                readerFactory, snapshotService, contextFactory, scheduler, taskRunner, taskStore,
                syncEngine, lockService, auditService, cdcManager, stateWriter, positionStore,
                taskMetricSampleRepository, alertStateRepository, new SyncProperties(),
                redisPolicy);
        lenient().when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(taskStore.find(any())).thenReturn(Optional.empty());
    }

    @Test
    void aRedisProjectStartsWithoutAJdbcTarget() {
        // A cache projection replaces the JDBC target: the project has a source and a Redis
        // target, but no target database at all. The start guard predates that shape and
        // rejected exactly this project ("no source or target database configured"), so a
        // fresh Redis project could never be started and its CDC consumer never began.
        Project project = new Project();
        project.setId(1L);
        project.setName("redis-proj");
        project.setSourceDbId(1L);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(7L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        config.getTables().add("orders");
        when(contextFactory.parseConfig(project)).thenReturn(config);
        when(cdcManager.strategyOf(any())).thenReturn(CaptureStrategy.BINLOG_CDC);

        service.start(1L);

        assertThat(project.getEnabled()).isTrue();
        verify(cdcManager).ensureStarted(project);
    }

    @Test
    void aRedisProjectCannotStartWithAStalePollingStrategy() {
        // Switching an existing polling project's target to Redis leaves its stored config
        // untouched: the strategy radio on the detail page no longer offers POLLING, so the
        // stale value only goes away when the config is saved again. Starting in between
        // scheduled JDBC cycles that cannot build a context (there is no target database) and
        // failed once per interval, while the cache consumer never ran. The save path already
        // refuses a non-CDC strategy for a cache target; start must answer the same way.
        Project project = new Project();
        project.setId(1L);
        project.setName("redis-proj");
        project.setSourceDbId(1L);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(7L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        config.getTables().add("orders");
        when(contextFactory.parseConfig(project)).thenReturn(config);

        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.redis.cdcOnly");

        assertThat(project.getEnabled()).isFalse();
        verify(cdcManager, never()).ensureStarted(any());
        verify(scheduler, never()).schedule(any());
    }

    @Test
    void aRedisProjectWithoutASavedProjectionStartsWithTheDefaultContract() {
        // Switching a JDBC project's target to Redis copies its old config verbatim, so the
        // row has no redisProjection node until the settings page is saved. The consumer
        // then failed with the internal English message "Redis projection settings are
        // missing for project N", which the detail page rendered as the task's error. Start
        // fills the documented default and persists it in the same save that enables the row.
        Project project = new Project();
        project.setId(1L);
        project.setName("redis-proj");
        project.setSourceDbId(1L);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(7L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        config.getTables().add("orders");
        when(contextFactory.parseConfig(project)).thenReturn(config);
        when(contextFactory.serializeConfig(any())).thenReturn("{\"serialized\":true}");
        when(redisPolicy.apply(any(), any(), any(), any())).thenAnswer(inv -> {
            SyncConfig applied = inv.getArgument(1);
            applied.setRedisProjection(RedisProjectionConfig.defaultFor(1L));
            return applied.getRedisProjection();
        });
        when(cdcManager.strategyOf(any())).thenReturn(CaptureStrategy.BINLOG_CDC);

        service.start(1L);

        verify(redisPolicy).apply(eq(project), eq(config),
                eq(CaptureStrategy.BINLOG_CDC), isNull());
        assertThat(project.getSyncConfig()).isEqualTo("{\"serialized\":true}");
        assertThat(project.getEnabled()).isTrue();
        verify(cdcManager).ensureStarted(project);
    }

    @Test
    void aCacheBuiltWithAnotherValueFormatMustBeRebuiltBeforeStart() {
        // Changing JSON <-> HASH leaves the existing keys in the other type: SET over a hash
        // (or HSET over a string) answers WRONGTYPE, the consumer parks, and the UI has no
        // hint of why. The position remembers the contract the keys were written with; a start
        // that no longer matches it is refused until the cache is rebuilt (with the sweep that
        // deletes the old keys).
        Project project = new Project();
        project.setId(1L);
        project.setName("redis-proj");
        project.setSourceDbId(1L);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(7L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        config.getTables().add("orders");
        config.setRedisProjection(RedisProjectionConfig.defaultFor(1L));
        when(contextFactory.parseConfig(project)).thenReturn(config);
        CdcPosition position = new CdcPosition();
        position.setProjectId(1L);
        position.setProjectionSignature("HASH|jync:1:");
        when(positionStore.find(1L)).thenReturn(Optional.of(position));

        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.redis.projection.rebuildRequired");

        assertThat(project.getEnabled()).isFalse();
        verify(cdcManager, never()).ensureStarted(any());
    }

    @Test
    void startRefusesAStoredPrefixAnotherProjectAlreadyOwns() {
        // A project switched away from Redis keeps its projection node in the config; switching
        // it back reuses that prefix without any settings save. If another project claimed the
        // prefix meanwhile, nothing has run the save-path conflict check for this row, and
        // starting would let both projects write the same keys. Start is the last gate.
        Project project = new Project();
        project.setId(1L);
        project.setName("redis-proj");
        project.setSourceDbId(1L);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(7L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        config.getTables().add("orders");
        config.setRedisProjection(RedisProjectionConfig.defaultFor(1L));
        when(contextFactory.parseConfig(project)).thenReturn(config);
        Project owner = new Project();
        owner.setId(2L);
        when(redisPolicy.conflictingProject(project, "jync:1:")).thenReturn(owner);

        assertThatThrownBy(() -> service.start(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.redis.prefixConflict");

        assertThat(project.getEnabled()).isFalse();
        verify(cdcManager, never()).ensureStarted(any());
    }

    @Test
    void changingTheValueFormatOnARunningCacheIsRefused() {
        // The live consumer built its serializer when it started; the new setting would only
        // reach a restarted one. Saving it mid-flight is not "applied", it is an invisible
        // divergence — and for a format switch it is the WRONGTYPE the next write hits.
        Project stored = new Project();
        stored.setId(1L);
        stored.setName("redis-proj");
        stored.setSourceDbId(1L);
        stored.setTargetKind(SyncTargetKind.REDIS);
        stored.setTargetRedisId(7L);
        stored.setEnabled(true);
        stored.setSyncConfig("{\"captureStrategy\":\"BINLOG_CDC\",\"tables\":[\"orders\"],"
                + "\"objectSelectionExplicit\":true,"
                + "\"redisProjection\":{\"keyPrefix\":\"jync:1:\",\"valueFormat\":\"JSON\"}}");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(stored));
        SyncConfig next = new SyncConfig();
        next.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        next.getTables().add("orders");
        next.setObjectSelectionExplicit(true);
        RedisProjectionConfig projection = RedisProjectionConfig.defaultFor(1L);
        projection.setValueFormat(RedisValueFormat.HASH);
        next.setRedisProjection(projection);

        assertThatThrownBy(() -> service.saveConfig(1L, next))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.project.redis.projection.running");

        verify(projectRepository, never()).save(any());
    }

    @Test
    void aRedisProjectRequiresARedisTarget() {
        Project project = new Project();
        project.setName("redis-proj");
        project.setSourceDbId(1L);
        project.setTargetKind(SyncTargetKind.REDIS);

        assertThatThrownBy(() -> service.save(project))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.redis.target.required");
    }

    @Test
    void aJdbcProjectClearsAStrayRedisTargetOnSave() {
        Project project = new Project();
        project.setName("jdbc-proj");
        project.setSourceDbId(1L);
        project.setTargetKind(SyncTargetKind.JDBC);
        project.setTargetRedisId(9L);

        Project saved = service.save(project);

        assertThat(saved.getTargetRedisId()).isNull();
    }

    @Test
    void aNoOpSaveOfALegacyJdbcProjectDoesNotWrite() {
        Project stored = new Project();
        stored.setId(1L);
        stored.setName("legacy");
        stored.setSourceDbId(1L);
        stored.setTargetDbId(2L);
        stored.setEnabled(false);
        when(projectRepository.findByName("legacy")).thenReturn(Optional.of(stored));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(stored));

        Project form = new Project();
        form.setId(1L);
        form.setName("legacy");
        form.setSourceDbId(1L);
        form.setTargetDbId(2L);
        form.setTargetKind(SyncTargetKind.JDBC);

        Project result = service.save(form);

        assertThat(result).isSameAs(stored);
        verify(projectRepository, never()).save(any());
        verify(auditService, never()).record(any(), any());
    }

    @Test
    void switchingTheTargetToRedisClearsTheOldSyncState() {
        Project stored = jdbcProject();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(stored));

        Project form = new Project();
        form.setId(1L);
        form.setName("proj");
        form.setSourceDbId(1L);
        form.setTargetDbId(2L);
        form.setTargetKind(SyncTargetKind.REDIS);
        form.setTargetRedisId(7L);

        service.save(form);

        verify(syncEngine).resetProject(1L);
        verify(checkpointRepository).deleteByProjectId(1L);
        // The anchor and the layouts belong to the JDBC target that was just left, but the
        // row itself also carries the Redis contract of any cache this project built, so it
        // is reset rather than deleted.
        verify(positionStore).resetAnchorKeepingProjectionSignature(1L);
        verify(positionStore, never()).delete(any());
    }

    @Test
    void aTargetKindSwitchKeepsTheCacheContractOnThePosition() {
        // Redis -> JDBC -> Redis is not a reason to forget what the keys in Redis are. The
        // position row still described the cache, and a value-format change made after
        // switching back is only detectable through its projection signature. Deleting the
        // row with the anchor lost that signature, so the next start let the baseline write
        // HSET over the existing JSON keys and park on a raw WRONGTYPE. The anchor and the
        // layouts are cleared (the destination changed); the contract is kept.
        Project stored = jdbcProject();
        stored.setTargetDbId(null);
        stored.setTargetKind(SyncTargetKind.REDIS);
        stored.setTargetRedisId(7L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(stored));

        Project form = new Project();
        form.setId(1L);
        form.setName("proj");
        form.setSourceDbId(1L);
        form.setTargetKind(SyncTargetKind.JDBC);

        service.save(form);

        verify(positionStore).resetAnchorKeepingProjectionSignature(1L);
        verify(positionStore, never()).delete(any());
    }

    @Test
    void aSourceChangeStillDropsThePositionRowEntirely() {
        // The cached keys the signature describes were built from the OLD source. Carrying
        // the signature across a source change would describe a cache this project no longer
        // produces, so the whole row goes, not just the anchor.
        Project stored = jdbcProject();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(stored));

        Project form = new Project();
        form.setId(1L);
        form.setName("proj");
        form.setSourceDbId(5L);
        form.setTargetDbId(2L);
        form.setTargetKind(SyncTargetKind.JDBC);

        service.save(form);

        verify(positionStore).delete(1L);
        verify(positionStore, never()).resetAnchorKeepingProjectionSignature(any());
    }

    @Test
    void switchingToRedisClearsTheJdbcTarget() {
        // The target dropdown is hidden for a Redis project but still submitted, so a switch
        // from JDBC carries the old targetDbId. Keeping it lets the target preview and the AI
        // assist read a database that is not this project's target; the JDBC branch already
        // clears the Redis id for the same reason.
        Project stored = jdbcProject();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(stored));

        Project form = new Project();
        form.setId(1L);
        form.setName("proj");
        form.setSourceDbId(1L);
        form.setTargetDbId(2L);
        form.setTargetKind(SyncTargetKind.REDIS);
        form.setTargetRedisId(7L);

        Project saved = service.save(form);

        assertThat(saved.getTargetDbId()).isNull();
        assertThat(saved.getTargetRedisId()).isEqualTo(7L);
    }

    @Test
    void switchingBackToJdbcClearsTheRedisTargetAndTheOldSyncState() {
        Project stored = jdbcProject();
        stored.setTargetDbId(null);
        stored.setTargetKind(SyncTargetKind.REDIS);
        stored.setTargetRedisId(7L);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(stored));

        Project form = new Project();
        form.setId(1L);
        form.setName("proj");
        form.setSourceDbId(1L);
        form.setTargetKind(SyncTargetKind.JDBC);

        Project saved = service.save(form);

        assertThat(saved.getTargetRedisId()).isNull();
        verify(syncEngine).resetProject(1L);
    }

    private static Project jdbcProject() {
        Project stored = new Project();
        stored.setId(1L);
        stored.setName("proj");
        stored.setSourceDbId(1L);
        stored.setTargetDbId(2L);
        stored.setEnabled(false);
        stored.setTargetKind(SyncTargetKind.JDBC);
        return stored;
    }
}
