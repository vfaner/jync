package com.qqmu.jync.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.Project;

/**
 * The Prometheus label maps need a project's and a connection's id and name — nothing else.
 * Both entities carry large {@code @Lob} payloads (the serialized sync config, the stored
 * credentials), and the scrape runs every few seconds, so it must not load whole rows to read
 * two short columns.
 */
@DataJpaTest
class MetricsLabelProjectionTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private DatabaseConfigRepository configRepository;

    @Test
    void projectsAreProjectedToIdAndName() {
        Project project = new Project();
        project.setName("orders");
        project.setSyncConfig("{\"tables\":[\"orders\"]}");
        projectRepository.save(project);

        Map<Long, String> names = projectRepository.findAllIdAndName().stream()
                .collect(Collectors.toMap(ProjectRepository.IdAndName::getId,
                        ProjectRepository.IdAndName::getName));

        assertThat(names).containsEntry(project.getId(), "orders");
    }

    @Test
    void connectionsAreProjectedToIdAndName() {
        DatabaseConfig config = new DatabaseConfig();
        config.setName("prod-mysql");
        configRepository.save(config);

        Map<Long, String> names = configRepository.findAllIdAndName().stream()
                .collect(Collectors.toMap(DatabaseConfigRepository.IdAndName::getId,
                        DatabaseConfigRepository.IdAndName::getName));

        assertThat(names).containsEntry(config.getId(), "prod-mysql");
    }
}
