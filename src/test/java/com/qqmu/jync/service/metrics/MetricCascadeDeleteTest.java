package com.qqmu.jync.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import com.qqmu.jync.model.AppMetricSample;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.DbMetricSample;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.TaskMetricSample;
import com.qqmu.jync.repository.AppMetricSampleRepository;
import com.qqmu.jync.repository.DatabaseConfigRepository;
import com.qqmu.jync.repository.DbMetricSampleRepository;
import com.qqmu.jync.repository.TaskMetricSampleRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;

/**
 * Metric rows belong to the object they describe: deleting the connection or the project
 * removes its history too, or the metadata store keeps growing with rows no page can reach.
 * The process table belongs to the JVM, not to any project, so it survives a project delete.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "sync.ai.enabled=false",
        "metrics.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:metriccascade;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory"
})
class MetricCascadeDeleteTest {

    @Autowired private ProjectService projectService;
    @Autowired private DatabaseConfigService databaseConfigService;
    @Autowired private DatabaseConfigRepository configRepository;
    @Autowired private DbMetricSampleRepository dbSamples;
    @Autowired private TaskMetricSampleRepository taskSamples;
    @Autowired private AppMetricSampleRepository appSamples;

    @Test
    void deletingAConnectionAlsoDeletesItsSamples() {
        DatabaseConfig doomed = connection("doomed");
        DatabaseConfig spared = connection("spared");
        dbSamples.save(dbSample(doomed.getId()));
        dbSamples.save(dbSample(spared.getId()));

        databaseConfigService.delete(doomed.getId());

        assertThat(dbSamples.findAll())
                .extracting(DbMetricSample::getConnectionId)
                .containsExactly(spared.getId());
    }

    @Test
    void deletingAProjectDeletesItsTaskSamplesButLeavesTheProcessSamples() {
        Project project = new Project();
        project.setName("metric-cascade");
        Project saved = projectService.save(project);
        taskSamples.save(taskSample(saved.getId()));
        appSamples.save(appSample());

        projectService.delete(saved.getId());

        assertThat(taskSamples.findAll()).isEmpty();
        assertThat(appSamples.findAll()).hasSize(1);
    }

    private DatabaseConfig connection(String name) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(DatabaseType.MYSQL);
        config.setRole(ConnectionRole.SOURCE);
        config.setHost("127.0.0.1");
        config.setPort(3306);
        return configRepository.save(config);
    }

    private DbMetricSample dbSample(Long connectionId) {
        DbMetricSample sample = new DbMetricSample();
        sample.setConnectionId(connectionId);
        sample.setSampledAt(Instant.now());
        sample.setOk(true);
        return sample;
    }

    private TaskMetricSample taskSample(Long projectId) {
        TaskMetricSample sample = new TaskMetricSample();
        sample.setProjectId(projectId);
        sample.setSampledAt(Instant.now());
        return sample;
    }

    private AppMetricSample appSample() {
        AppMetricSample sample = new AppMetricSample();
        sample.setSampledAt(Instant.now());
        return sample;
    }
}
