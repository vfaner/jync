package com.synctool.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import com.synctool.model.ConnectionRole;
import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;
import com.synctool.model.Project;
import com.synctool.repository.DatabaseConfigRepository;
import com.synctool.repository.ProjectRepository;
import com.synctool.service.connection.ConnectionTestService;
import com.synctool.service.connection.DataSourceManager;
import com.synctool.util.CryptoUtil;

/**
 * Backfill against a real H2 database: rows created before the role column existed have NULL
 * there, and ddl-auto=update can only add the column as nullable. The startup backfill must
 * infer the role from project references and never leave a row without one.
 */
@DataJpaTest
class DatabaseConfigRoleBackfillDataTest {

    @Autowired private DatabaseConfigRepository repository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestEntityManager testEntityManager;

    private DatabaseConfigService service;

    private DatabaseConfig connection(String name) {
        DatabaseConfig c = new DatabaseConfig();
        c.setName(name);
        c.setType(DatabaseType.MYSQL);
        c.setHost("localhost");
        c.setRole(null);
        return c;
    }

    @BeforeEach
    void setUp() {
        service = new DatabaseConfigService(repository, projectRepository,
                mock(DataSourceManager.class), mock(ConnectionTestService.class), mock(CryptoUtil.class));
    }

    @Test
    void backfillsNullRolesFromProjectReferences() {
        DatabaseConfig unreferenced = repository.save(connection("a-unreferenced"));
        DatabaseConfig targetOnly = repository.save(connection("b-target"));
        DatabaseConfig sourceOnly = repository.save(connection("c-source"));
        DatabaseConfig alreadyClassified = connection("d-ok");
        alreadyClassified.setRole(ConnectionRole.TARGET);
        repository.save(alreadyClassified);

        Project asTarget = new Project();
        asTarget.setName("uses-b-as-target");
        asTarget.setTargetDbId(targetOnly.getId());
        projectRepository.save(asTarget);

        Project asSource = new Project();
        asSource.setName("uses-c-as-source");
        asSource.setSourceDbId(sourceOnly.getId());
        projectRepository.save(asSource);

        repository.flush();
        // Simulate rows written by an older release: the column exists but holds NULL.
        jdbcTemplate.update("update database_config set role = null where name in "
                + "('a-unreferenced','b-target','c-source')");

        service.backfillRoles();
        repository.flush();
        testEntityManager.clear();

        assertThat(repository.findById(unreferenced.getId()).orElseThrow().getRole())
                .isEqualTo(ConnectionRole.SOURCE);
        assertThat(repository.findById(targetOnly.getId()).orElseThrow().getRole())
                .isEqualTo(ConnectionRole.TARGET);
        assertThat(repository.findById(sourceOnly.getId()).orElseThrow().getRole())
                .isEqualTo(ConnectionRole.SOURCE);
        assertThat(repository.findById(alreadyClassified.getId()).orElseThrow().getRole())
                .isEqualTo(ConnectionRole.TARGET);

        assertThat(repository.findByRoleOrderByNameAsc(ConnectionRole.TARGET))
                .extracting(DatabaseConfig::getName)
                .containsExactly("b-target", "d-ok");
    }
}
