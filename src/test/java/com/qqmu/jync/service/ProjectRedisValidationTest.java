package com.qqmu.jync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.RedisProjectionConfig;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.repository.AlertStateRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ChunkCheckpointRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.repository.SyncProgressRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcPositionStore;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReaderFactory;
import com.qqmu.jync.service.metadata.MetadataSnapshotService;
import com.qqmu.jync.service.projection.redis.RedisProjectionPolicy;
import com.qqmu.jync.service.projection.redis.RedisValueFormat;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;
import com.qqmu.jync.service.task.SyncContextFactory;
import com.qqmu.jync.service.task.SyncLockService;
import com.qqmu.jync.service.task.SyncScheduler;
import com.qqmu.jync.service.task.SyncTaskRunner;
import com.qqmu.jync.service.task.SyncTaskStore;

/**
 * A Redis target is a cache projection, not a database: it has no DDL, no cursor and no
 * polling read. These tests pin the server-side rules that make that promise true, because
 * the form is only one of the ways a config can arrive (a direct POST, the AJAX twin, the
 * bulk planner and the AI assist all reach the same service).
 */
@ExtendWith(MockitoExtension.class)
class ProjectRedisValidationTest {

    private static final long PROJECT_ID = 1L;
    private static final long SOURCE_ID = 2L;
    private static final long REDIS_TARGET_ID = 9L;

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
    @Mock private RedisTargetRepository redisTargetRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private ProjectService service;

    @BeforeEach
    void setUp() {
        RedisProjectionPolicy policy = new RedisProjectionPolicy(
                redisTargetRepository, projectRepository, contextFactory);
        service = new ProjectService(projectRepository, databaseConfigRepository,
                progressRepository, changeLogRepository, checkpointRepository, dataSourceManager,
                readerFactory, snapshotService, contextFactory, scheduler, taskRunner, taskStore,
                syncEngine, lockService, auditService, cdcManager, stateWriter, positionStore,
                taskMetricSampleRepository, alertStateRepository, new SyncProperties(), policy);
        lenient().when(projectRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(taskStore.find(any())).thenReturn(Optional.empty());
        lenient().when(contextFactory.serializeConfig(any())).thenAnswer(i -> {
            try {
                return mapper.writeValueAsString(i.getArgument(0));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        lenient().when(contextFactory.parseConfig(any(Project.class)))
                .thenAnswer(i -> new SyncConfig());
        lenient().when(redisTargetRepository.existsById(REDIS_TARGET_ID)).thenReturn(true);
        lenient().when(databaseConfigRepository.findById(SOURCE_ID))
                .thenReturn(Optional.of(source(DatabaseType.MYSQL)));
    }

    @Test
    void redisTargetRejectsPollingStrategy() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(redisProject()));

        assertThatThrownBy(() -> service.saveConfig(PROJECT_ID, new SyncConfig(), "POLLING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.redis.cdcOnly");
        assertThatThrownBy(() -> service.saveConfig(PROJECT_ID, new SyncConfig(), "FULL_COMPARE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.redis.cdcOnly");
    }

    @Test
    void redisTargetRequiresExplicitCdcStrategy() {
        // No stored strategy and none submitted: the effective strategy would be POLLING,
        // which a Redis target must never run.
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(redisProject()));

        assertThatThrownBy(() -> service.saveConfig(PROJECT_ID, new SyncConfig(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.redis.cdcOnly");
    }

    @Test
    void redisTargetForbidsStructureSync() throws Exception {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(redisProject()));
        SyncConfig config = new SyncConfig();
        config.setSyncStructure(true);
        config.setSyncIndexes(true);
        config.setSyncViews(true);
        config.setSyncProcedures(true);
        config.setSyncData(false);

        Project saved = service.saveConfig(PROJECT_ID, config, "BINLOG_CDC");

        SyncConfig stored = mapper.readValue(saved.getSyncConfig(), SyncConfig.class);
        assertThat(stored.isSyncStructure()).isFalse();
        assertThat(stored.isSyncIndexes()).isFalse();
        assertThat(stored.isSyncViews()).isFalse();
        assertThat(stored.isSyncProcedures()).isFalse();
        assertThat(stored.isSyncData()).isTrue();
    }

    @Test
    void prefixIsRequiredAndNormalized() throws Exception {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(redisProject()));

        SyncConfig submitted = new SyncConfig();
        submitted.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        RedisProjectionConfig projection = new RedisProjectionConfig();
        projection.setKeyPrefix("  myapp  ");
        submitted.setRedisProjection(projection);
        Project saved = service.saveConfig(PROJECT_ID, submitted, "BINLOG_CDC");
        assertThat(mapper.readValue(saved.getSyncConfig(), SyncConfig.class)
                .getRedisProjection().getKeyPrefix()).isEqualTo("myapp:");

        SyncConfig blank = new SyncConfig();
        blank.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        blank.setRedisProjection(new RedisProjectionConfig());
        Project savedBlank = service.saveConfig(PROJECT_ID, blank, "BINLOG_CDC");
        assertThat(mapper.readValue(savedBlank.getSyncConfig(), SyncConfig.class)
                .getRedisProjection().getKeyPrefix()).isEqualTo("jync:1:");
    }

    @Test
    void projectionDefaultsAreFilledIn() throws Exception {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(redisProject()));
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);

        Project saved = service.saveConfig(PROJECT_ID, config, "BINLOG_CDC");

        RedisProjectionConfig projection = mapper.readValue(saved.getSyncConfig(), SyncConfig.class)
                .getRedisProjection();
        assertThat(projection).isNotNull();
        assertThat(projection.getValueFormat()).isEqualTo(RedisValueFormat.JSON);
        assertThat(projection.getTtlSeconds()).isZero();
        assertThat(projection.getMaxValueBytes()).isEqualTo(1_048_576);
    }

    @Test
    void duplicatePrefixOnTheSameRedisTargetIsRejected() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(redisProject()));
        Project sibling = new Project();
        sibling.setId(2L);
        sibling.setName("sibling");
        sibling.setTargetKind(SyncTargetKind.REDIS);
        sibling.setTargetRedisId(REDIS_TARGET_ID);
        SyncConfig siblingConfig = new SyncConfig();
        RedisProjectionConfig siblingProjection = new RedisProjectionConfig();
        siblingProjection.setKeyPrefix("myapp:");
        siblingConfig.setRedisProjection(siblingProjection);
        when(projectRepository.findByTargetRedisId(REDIS_TARGET_ID))
                .thenReturn(List.of(sibling));
        when(contextFactory.parseConfig(sibling)).thenReturn(siblingConfig);

        SyncConfig config = new SyncConfig();
        RedisProjectionConfig projection = new RedisProjectionConfig();
        projection.setKeyPrefix("myapp");
        config.setRedisProjection(projection);

        assertThatThrownBy(() -> service.saveConfig(PROJECT_ID, config, "BINLOG_CDC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.redis.prefixConflict");
    }

    @Test
    void aSourceWithoutCdcIsRejected() {
        // The strategy is already stored; this save (a column-rule or AI write) carries no
        // strategy name, so only the source check can catch an endpoint that no longer
        // supports log capture.
        Project project = redisProject();
        project.setSyncConfig("{\"captureStrategy\":\"BINLOG_CDC\"}");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(databaseConfigRepository.findById(SOURCE_ID))
                .thenReturn(Optional.of(source(DatabaseType.H2)));

        assertThatThrownBy(() -> service.saveConfig(PROJECT_ID, new SyncConfig(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.redis.sourceNoCdc");
    }

    @Test
    void creatingARedisProjectRequiresAnExistingTarget() {
        Project form = new Project();
        form.setName("cache-proj");
        form.setSourceDbId(SOURCE_ID);
        form.setTargetKind(SyncTargetKind.REDIS);
        form.setTargetRedisId(404L);

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.redis.target.not.found");
    }

    @Test
    void creatingARedisProjectFromASourceWithoutCdcIsRejected() {
        when(databaseConfigRepository.findById(SOURCE_ID))
                .thenReturn(Optional.of(source(DatabaseType.H2)));
        Project form = new Project();
        form.setName("cache-proj");
        form.setSourceDbId(SOURCE_ID);
        form.setTargetKind(SyncTargetKind.REDIS);
        form.setTargetRedisId(REDIS_TARGET_ID);

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.project.redis.sourceNoCdc");
    }

    @Test
    void aJdbcProjectDropsStaleProjectionSettings() throws Exception {
        Project stored = new Project();
        stored.setId(PROJECT_ID);
        stored.setName("jdbc-proj");
        stored.setSourceDbId(SOURCE_ID);
        stored.setTargetDbId(3L);
        stored.setEnabled(false);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(stored));

        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.BINLOG_CDC);
        config.setRedisProjection(new RedisProjectionConfig());
        config.setSyncStructure(true);

        Project saved = service.saveConfig(PROJECT_ID, config, "BINLOG_CDC");

        SyncConfig persisted = mapper.readValue(saved.getSyncConfig(), SyncConfig.class);
        assertThat(persisted.getRedisProjection()).isNull();
        assertThat(persisted.isSyncStructure()).isTrue();
    }

    private static Project redisProject() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("redis-proj");
        project.setSourceDbId(SOURCE_ID);
        project.setTargetKind(SyncTargetKind.REDIS);
        project.setTargetRedisId(REDIS_TARGET_ID);
        project.setEnabled(false);
        return project;
    }

    private static DatabaseConfig source(DatabaseType type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(SOURCE_ID);
        config.setName("source");
        config.setType(type);
        return config;
    }
}
