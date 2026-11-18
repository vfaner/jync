package com.qqmu.jync.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * What a signed-in account actually sees on the shared pages. The filter chain and the
 * ownership guards decide what is <em>allowed</em>; this class pins that the page does not
 * offer a button the server would refuse, and that the role badge names the caller's role.
 *
 * <p>Owner scoping is per row: an operator gets the actions on its own rows only, while the
 * read-only account gets none anywhere and the administrator keeps every row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:rolerendering;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/rolerendering-logs/jync.log"
})
class RoleRenderingTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private DatabaseConfigService databaseConfigs;
    @Autowired private ProjectService projects;
    @Autowired private EtlTaskRepository etlTasks;
    @Autowired private MigrationConnectionRepository migrationConnections;
    @Autowired private RedisTargetRepository redisTargets;
    @Autowired private ChangeLogRepository changeLogs;

    @Test
    void anOpsGetsRowActionsOnlyOnItsOwnProjectRows() throws Exception {
        DatabaseConfig aliceSrc = connection(unique("alice-src"), "alice", ConnectionRole.SOURCE);
        DatabaseConfig aliceDst = connection(unique("alice-dst"), "alice", ConnectionRole.TARGET);
        DatabaseConfig bobSrc = connection(unique("bob-src"), "bob", ConnectionRole.SOURCE);
        DatabaseConfig bobDst = connection(unique("bob-dst"), "bob", ConnectionRole.TARGET);
        Project alices = project(unique("alice-proj"), aliceSrc, aliceDst, "alice");
        Project bobs = project(unique("bob-proj"), bobSrc, bobDst, "bob");

        String html = page("/projects", asOps("alice"));

        assertThat(html).contains("/projects/" + alices.getId() + "/edit")
                .contains("/projects/" + alices.getId() + "/delete")
                // The performance page is owner-only too: it opens the project's own
                // measured data, which the read-only account may not see at all.
                .contains("/projects/" + alices.getId() + "/performance");
        assertThat(html).doesNotContain("/projects/" + bobs.getId() + "/edit")
                .doesNotContain("/projects/" + bobs.getId() + "/delete")
                .doesNotContain("/projects/" + bobs.getId() + "/performance");
    }

    @Test
    void aViewerSeesNoWriteActionsOnTheBusinessLists() throws Exception {
        DatabaseConfig source = connection(unique("view-src"), "alice", ConnectionRole.SOURCE);
        DatabaseConfig target = connection(unique("view-dst"), "alice", ConnectionRole.TARGET);
        Project project = project(unique("view-proj"), source, target, "alice");
        EtlTask task = etlTask(unique("view-etl"), "alice", source.getId(), target.getId());

        Map<String, List<String>> forbidden = new LinkedHashMap<>();
        forbidden.put("/projects", List.of(
                "/projects/" + project.getId() + "/edit",
                "/projects/" + project.getId() + "/delete",
                "/projects/" + project.getId() + "/performance",
                "/projects/new"));
        forbidden.put("/etl", List.of(
                "/etl/" + task.getId() + "/edit",
                "/etl/" + task.getId() + "/delete",
                "/etl/new"));
        forbidden.put("/databases", List.of(
                "/databases/" + source.getId() + "/edit",
                "/databases/" + source.getId() + "/delete",
                "/databases/" + source.getId() + "/performance",
                "/databases/new"));
        // The Redis-target and migration-connection listings are WRITER_ONLY_GET: they
        // hold endpoint configuration, so a viewer is refused in the filter chain and
        // never sees them at all.
        forbidden.put("/migrations", List.of("/migrations/new", "/migrations/connections"));

        for (Map.Entry<String, List<String>> page : forbidden.entrySet()) {
            String html = page(page.getKey(), asViewer());
            assertThat(html).as(page.getKey())
                    .doesNotContain(page.getValue().toArray(String[]::new))
                    .doesNotContain("data-action=");
        }
    }

    @Test
    void theRoleBadgeNamesEachRole() throws Exception {
        assertThat(page("/projects", asAdmin()))
                .contains("role-admin").contains("管理员");
        assertThat(page("/projects", asOps("alice")))
                .contains("role-ops").contains("运维");
        assertThat(page("/projects", asViewer()))
                .contains("role-viewer").contains("访客");
    }

    @Test
    void anOpsCannotResolveAnotherOperatorsSkippedDdl() throws Exception {
        DatabaseConfig aliceSrc = connection(unique("ddl-src"), "alice", ConnectionRole.SOURCE);
        DatabaseConfig aliceDst = connection(unique("ddl-dst"), "alice", ConnectionRole.TARGET);
        Project alices = project(unique("ddl-proj"), aliceSrc, aliceDst, "alice");
        ChangeLog entry = skippedDdl(alices.getId());

        for (String action : List.of("execute", "retry", "dismiss")) {
            mvc.perform(post("/change-logs/" + entry.getId() + "/" + action)
                            .param("sql", "ALTER TABLE t ADD COLUMN c INT")
                            .with(asOps("bob")).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/?denied"));
        }
    }

    @Test
    void bulkPlanningRefusesAnotherOperatorsSourceConnection() throws Exception {
        DatabaseConfig aliceSrc = connection(unique("bulk-src"), "alice", ConnectionRole.SOURCE);
        DatabaseConfig aliceDst = connection(unique("bulk-dst"), "alice", ConnectionRole.TARGET);

        String html = mvc.perform(post("/projects/bulk/preview").locale(Locale.SIMPLIFIED_CHINESE)
                        .with(asOps("bob")).with(csrf())
                        .param("sourceDbId", aliceSrc.getId().toString())
                        .param("targetDbId", aliceDst.getId().toString())
                        .param("taskCount", "2")
                        .param("namePrefix", "bulk"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("该资源属于其他账号")
                .doesNotContain("error.ownership.reference");
    }

    // ------------------------------------------------------------------ fixtures

    private ChangeLog skippedDdl(Long projectId) {
        ChangeLog entry = new ChangeLog();
        entry.setProjectId(projectId);
        entry.setObjectType(ObjectType.TABLE);
        entry.setChangeType(ChangeType.ALTER);
        entry.setOccurredAt(Instant.now());
        return changeLogs.save(entry);
    }

    private DatabaseConfig connection(String name, String owner, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(DatabaseType.MYSQL);
        config.setRole(role);
        config.setHost("127.0.0.1");
        config.setPort(3306);
        config.setDatabaseName("demo");
        config.setUsername("sync");
        return databaseConfigs.save(config, "pw", null, guardOf(owner));
    }

    private Project project(String name, DatabaseConfig src, DatabaseConfig dst, String owner) {
        Project project = new Project();
        project.setName(name);
        project.setSourceDbId(src.getId());
        project.setTargetDbId(dst.getId());
        return projects.save(project, guardOf(owner));
    }

    private EtlTask etlTask(String name, String owner, Long sourceDbId, Long targetDbId) {
        EtlTask task = new EtlTask();
        task.setName(name);
        task.setSourceDbId(sourceDbId);
        task.setTargetDbId(targetDbId);
        task.setSourceSql("select 1");
        task.setTargetTable("orders");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        task.setOwnerUsername(owner);
        return etlTasks.save(task);
    }

    private RedisTarget redisTarget(String name, String owner) {
        RedisTarget target = new RedisTarget();
        target.setName(name);
        target.setHost("127.0.0.1");
        target.setPort(6379);
        target.setOwnerUsername(owner);
        return redisTargets.save(target);
    }

    private MigrationConnection migrationConnection(String name, String owner) {
        MigrationConnection connection = new MigrationConnection();
        connection.setName(name);
        connection.setKind(MigrationConnectionKind.ELASTICSEARCH);
        connection.setBaseUrl("http://127.0.0.1:9200");
        connection.setAuthType(MigrationConnectionAuthType.NONE);
        connection.setVerifyTls(true);
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        connection.setOwnerUsername(owner);
        return migrationConnections.save(connection);
    }

    private static String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    private static AccessGuard guardOf(String username) {
        return AccessGuard.of(username, "admin".equals(username) ? UserRole.ADMIN : UserRole.OPS);
    }

    // ------------------------------------------------------------------ requests

    /** A page read in Chinese, the UI language the role-badge assertions expect. */
    private String page(String url, RequestPostProcessor auth) throws Exception {
        return mvc.perform(get(url).locale(Locale.SIMPLIFIED_CHINESE).with(auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static RequestPostProcessor asOps(String username) {
        return as(username, UserRole.OPS);
    }

    private static RequestPostProcessor asAdmin() {
        return as("admin", UserRole.ADMIN);
    }

    private static RequestPostProcessor asViewer() {
        return as("view", UserRole.VIEWER);
    }

    private static RequestPostProcessor as(String username, UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
