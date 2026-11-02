package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationRun;
import com.qqmu.jync.model.MigrationRunStatus;
import com.qqmu.jync.model.MigrationRunTrigger;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;

@DataJpaTest
class MigrationRepositoryTest {

    @Autowired
    private MigrationConnectionRepository connectionRepository;

    @Autowired
    private MigrationTaskRepository taskRepository;

    @Autowired
    private MigrationRunRepository runRepository;

    private MigrationConnection connection(String name, MigrationConnectionKind kind) {
        MigrationConnection connection = new MigrationConnection();
        connection.setName(name);
        connection.setKind(kind);
        connection.setBaseUrl("http://127.0.0.1:9200");
        connection.setAuthType(MigrationConnectionAuthType.NONE);
        connection.setVerifyTls(true);
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        return connectionRepository.save(connection);
    }

    private MigrationTask task(MigrationType type, Long sourceId, Long targetId, boolean enabled) {
        MigrationTask task = new MigrationTask();
        task.setName(type.key() + "-" + taskRepository.count());
        task.setType(type);
        task.setSourceConnectionId(sourceId);
        task.setTargetConnectionId(targetId);
        task.setOptionsJson("{}");
        task.setEnabled(enabled);
        task.setCronExpression(enabled ? "0 0 2 * * *" : null);
        return taskRepository.save(task);
    }

    private MigrationRun run(Long taskId, MigrationRunStatus status, Instant startedAt) {
        MigrationRun run = new MigrationRun();
        run.setMigrationTaskId(taskId);
        run.setStatus(status);
        run.setTriggerType(MigrationRunTrigger.MANUAL);
        run.setStartedAt(startedAt);
        return runRepository.save(run);
    }

    @Test
    void connectionsAreFoundByNameAndKind() {
        connection("es-prod", MigrationConnectionKind.ELASTICSEARCH);
        connection("easysearch-prod", MigrationConnectionKind.EASYSEARCH);

        assertThat(connectionRepository.findByName("es-prod")).isPresent();
        assertThat(connectionRepository.findByKindInOrderByNameAsc(
                List.of(MigrationConnectionKind.EASYSEARCH)))
                .extracting(MigrationConnection::getName)
                .containsExactly("easysearch-prod");
    }

    @Test
    void tasksAreListedPerTypeNewestFirstAndFilteredByEnabled() {
        MigrationConnection source = connection("es", MigrationConnectionKind.ELASTICSEARCH);
        MigrationConnection target = connection("easysearch", MigrationConnectionKind.EASYSEARCH);
        MigrationTask first = task(MigrationType.ES_TO_EASYSEARCH, source.getId(), target.getId(), true);
        MigrationTask second = task(MigrationType.ES_TO_EASYSEARCH, source.getId(), target.getId(), false);

        assertThat(taskRepository.findByTypeOrderByIdDesc(MigrationType.ES_TO_EASYSEARCH))
                .extracting(MigrationTask::getId)
                .containsExactly(second.getId(), first.getId());
        assertThat(taskRepository.findByEnabledTrueAndType(MigrationType.ES_TO_EASYSEARCH))
                .extracting(MigrationTask::getId)
                .containsExactly(first.getId());
        assertThat(taskRepository.countByType(MigrationType.ES_TO_EASYSEARCH)).isEqualTo(2);
    }

    @Test
    void aConnectionUsedAsSourceOrTargetIsCounted() {
        MigrationConnection source = connection("es", MigrationConnectionKind.ELASTICSEARCH);
        MigrationConnection target = connection("easysearch", MigrationConnectionKind.EASYSEARCH);
        MigrationConnection idle = connection("idle", MigrationConnectionKind.EASYSEARCH);
        task(MigrationType.ES_TO_EASYSEARCH, source.getId(), target.getId(), false);

        assertThat(taskRepository.countBySourceConnectionIdOrTargetConnectionId(
                source.getId(), source.getId())).isEqualTo(1);
        assertThat(taskRepository.countBySourceConnectionIdOrTargetConnectionId(
                target.getId(), target.getId())).isEqualTo(1);
        assertThat(taskRepository.countBySourceConnectionIdOrTargetConnectionId(
                idle.getId(), idle.getId())).isZero();
    }

    @Test
    void theDetailPageReadsTheFiftyMostRecentRuns() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 55; i++) {
            run(7L, MigrationRunStatus.SUCCESS, base.plusSeconds(i));
        }

        List<MigrationRun> recent = runRepository.findTop50ByMigrationTaskIdOrderByStartedAtDesc(7L);

        assertThat(recent).hasSize(50);
        assertThat(recent.get(0).getStartedAt()).isEqualTo(base.plusSeconds(54));
        assertThat(recent.get(49).getStartedAt()).isEqualTo(base.plusSeconds(5));
    }

    @Test
    void startupRecoveryCanFindEveryRunLeftRunning() {
        run(1L, MigrationRunStatus.RUNNING, Instant.now());
        run(2L, MigrationRunStatus.SUCCESS, Instant.now());

        assertThat(runRepository.findByStatus(MigrationRunStatus.RUNNING)).hasSize(1);
        assertThat(runRepository.countByMigrationTaskIdAndStatus(1L, MigrationRunStatus.RUNNING))
                .isEqualTo(1);
    }
}
