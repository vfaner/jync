package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.AccessGuard;

/**
 * The connection dropdowns are scoped to the account the form is acting for.
 *
 * <p>A new project belongs to its creator, so its selectors list the caller's connections; an
 * existing project keeps its owner, so even an administrator editing it sees that owner's
 * connections. The server refuses foreign references either way (see
 * {@code OwnerWriteRulesTest}); the selectors exist so the operator is not offered a choice
 * the save would then reject.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:referencescope;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/referencescope-logs/jync.log"
})
class ReferenceScopeViewTest {

    private static final AccessGuard ALICE = AccessGuard.of("alice", UserRole.OPS);
    private static final AccessGuard BOB = AccessGuard.of("bob", UserRole.OPS);
    private static final AccessGuard ADMIN = AccessGuard.of("admin", UserRole.ADMIN);

    @Autowired private MockMvc mvc;
    @Autowired private DatabaseConfigService databaseConfigs;
    @Autowired private ProjectService projects;

    @Test
    void theNewFormListsOnlyTheCallersConnections() throws Exception {
        databaseConfigs.save(connection("alice-new-src", ConnectionRole.SOURCE), "pw", null, ALICE);
        databaseConfigs.save(connection("bob-new-src", ConnectionRole.SOURCE), "pw", null, BOB);

        String page = mvc.perform(get("/projects/new").with(user("alice").roles("OPS")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("alice-new-src").doesNotContain("bob-new-src");
    }

    @Test
    void anEditFormFollowsTheProjectsOwnerNotTheCaller() throws Exception {
        DatabaseConfig aliceSrc = databaseConfigs.save(
                connection("alice-edit-src", ConnectionRole.SOURCE), "pw", null, ALICE);
        DatabaseConfig aliceDst = databaseConfigs.save(
                connection("alice-edit-dst", ConnectionRole.TARGET), "pw", null, ALICE);
        databaseConfigs.save(connection("admin-own-src", ConnectionRole.SOURCE), "pw", null,
                ADMIN);

        Project project = new Project();
        project.setName("scope-edit");
        project.setSourceDbId(aliceSrc.getId());
        project.setTargetDbId(aliceDst.getId());
        Project saved = projects.save(project, ALICE);

        String page = mvc.perform(get("/projects/" + saved.getId() + "/edit")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("alice-edit-src").doesNotContain("admin-own-src");
    }

    /** The smallest connection the service accepts: MySQL, TCP, one database. */
    private static DatabaseConfig connection(String name, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(DatabaseType.MYSQL);
        config.setRole(role);
        config.setHost("127.0.0.1");
        config.setPort(3306);
        config.setDatabaseName("demo");
        config.setUsername("sync");
        return config;
    }
}
