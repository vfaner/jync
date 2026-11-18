package com.qqmu.jync.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationTask;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.RedisTarget;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.repository.MigrationConnectionRepository;
import com.qqmu.jync.repository.MigrationTaskRepository;
import com.qqmu.jync.repository.RedisTargetRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * Every business entry point honours the two-question rule from the v2.4.0 ownership design:
 * the filter chain admits the two write roles, and the handler then asks
 * {@link AccessGuard#canEdit} about the resource's owner.
 *
 * <p>A row whose owner is someone else must be readable by nobody in the write roles except
 * an administrator: the HTML pages answer with the shared {@code /?denied} redirect, the
 * {@code /api} endpoints with a JSON 403. Viewer denial happens one layer earlier, in the
 * filter chain, and is pinned here so the two layers cannot drift.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ownershipmatrix;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/ownershipmatrix-logs/jync.log"
})
class OwnershipMatrixTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private DatabaseConfigService databaseConfigs;
    @Autowired private ProjectService projects;
    @Autowired private EtlTaskRepository etlTasks;
    @Autowired private EtlBatchRepository etlBatches;
    @Autowired private MigrationConnectionRepository migrationConnections;
    @Autowired private MigrationTaskRepository migrationTasks;
    @Autowired private RedisTargetRepository redisTargets;

    // ---------------------------------------------------------------- database

    @Test
    void anOpsCannotOpenAnotherOperatorsDatabase() throws Exception {
        DatabaseConfig alices = connection(unique("alice-db"), "alice", ConnectionRole.SOURCE);
        denied(get("/databases/" + alices.getId() + "/edit").with(asOps("bob")));
    }

    @Test
    void anOpsCanOpenItsOwnDatabase() throws Exception {
        DatabaseConfig own = connection(unique("bob-db"), "bob", ConnectionRole.SOURCE);
        mvc.perform(get("/databases/" + own.getId() + "/edit").with(asOps("bob")))
                .andExpect(status().isOk());
    }

    @Test
    void anAdminCanOpenAnOperatorsDatabase() throws Exception {
        DatabaseConfig alices = connection(unique("alice-db-admin"), "alice",
                ConnectionRole.SOURCE);
        mvc.perform(get("/databases/" + alices.getId() + "/edit").with(asAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void anOpsCannotSaveOverAnotherOperatorsDatabase() throws Exception {
        DatabaseConfig alices = connection(unique("alice-db-save"), "alice",
                ConnectionRole.SOURCE);
        denied(post("/databases/save").with(asOps("bob")).with(csrf())
                .param("id", alices.getId().toString())
                .param("name", unique("hijacked"))
                .param("role", "SOURCE")
                .param("type", "MYSQL")
                .param("host", "127.0.0.1")
                .param("port", "3306")
                .param("databaseName", "demo")
                .param("username", "sync"));
        assertThat(databaseConfigs.findById(alices.getId()).orElseThrow().getName())
                .isEqualTo(alices.getName());
    }

    @Test
    void anOpsCannotDeleteAnotherOperatorsDatabase() throws Exception {
        DatabaseConfig alices = connection(unique("alice-db-del"), "alice",
                ConnectionRole.SOURCE);
        denied(post("/databases/" + alices.getId() + "/delete").with(asOps("bob")).with(csrf()));
        assertThat(databaseConfigs.findById(alices.getId())).isPresent();
    }

    @Test
    void aViewerIsRefusedAtTheFilterChain() throws Exception {
        DatabaseConfig alices = connection(unique("alice-db-viewer"), "alice",
                ConnectionRole.SOURCE);
        denied(get("/databases/" + alices.getId() + "/edit").with(asViewer()));
        denied(post("/databases/" + alices.getId() + "/delete")
                .with(asViewer()).with(csrf()));
    }

    @Test
    void theDatabaseApiRefusesForeignConnections() throws Exception {
        DatabaseConfig bobs = connection(unique("bob-db-api"), "bob", ConnectionRole.SOURCE);
        DatabaseConfig alices = connection(unique("alice-db-api"), "alice",
                ConnectionRole.SOURCE);

        forbidden(post("/api/databases/" + alices.getId() + "/test")
                .with(asOps("bob")).with(csrf()));
        forbidden(get("/api/databases/" + alices.getId() + "/tables").with(asOps("bob")));
        // One foreign id in the list is enough: the dots would dial her database.
        forbidden(get("/api/databases/health")
                .param("ids", bobs.getId() + "," + alices.getId()).with(asOps("bob")));

        mvc.perform(get("/api/databases/health").param("ids", bobs.getId().toString())
                        .with(asOps("bob")))
                .andExpect(status().isOk());
    }

    @Test
    void theDatabasePerformanceEndpointsRefuseForeignConnections() throws Exception {
        DatabaseConfig alices = connection(unique("alice-db-perf"), "alice",
                ConnectionRole.SOURCE);
        denied(get("/databases/" + alices.getId() + "/performance").with(asOps("bob")));
        denied(get("/databases/" + alices.getId() + "/metrics").with(asOps("bob")));
        mvc.perform(get("/databases/" + alices.getId() + "/performance").with(asAdmin()))
                .andExpect(status().isOk());
    }

    // ----------------------------------------------------------------- project

    @Test
    void anOpsOpensItsOwnProjectAndAnAdminOpensAnyProject() throws Exception {
        Fixture fixture = foreignProject("alice");
        mvc.perform(get("/projects/" + fixture.project().getId() + "/edit").with(asAdmin()))
                .andExpect(status().isOk());

        DatabaseConfig bobSrc = connection(unique("bob-proj-src"), "bob", ConnectionRole.SOURCE);
        DatabaseConfig bobDst = connection(unique("bob-proj-dst"), "bob", ConnectionRole.TARGET);
        Project own = project(unique("bob-project"), bobSrc, bobDst, "bob");
        mvc.perform(get("/projects/" + own.getId() + "/edit").with(asOps("bob")))
                .andExpect(status().isOk());
    }

    @Test
    void theProjectWritesRefuseAnotherOperatorsProject() throws Exception {
        Fixture fixture = foreignProject("alice");
        Long id = fixture.project().getId();
        DatabaseConfig bobSrc = connection(unique("bob-write-src"), "bob", ConnectionRole.SOURCE);
        DatabaseConfig bobDst = connection(unique("bob-write-dst"), "bob", ConnectionRole.TARGET);

        // The save names the caller's own connections, so only the stored owner's check can
        // refuse it.
        denied(post("/projects/save").with(asOps("bob")).with(csrf())
                .param("id", id.toString())
                .param("name", unique("hijacked-project"))
                .param("sourceDbId", bobSrc.getId().toString())
                .param("targetDbId", bobDst.getId().toString()));
        denied(post("/projects/" + id + "/config").with(asOps("bob")).with(csrf()));
        denied(post("/projects/" + id + "/config").with(asOps("bob")).with(csrf())
                .header("X-Jync-Ajax", "1"));
        denied(post("/projects/" + id + "/column-rules").with(asOps("bob")).with(csrf())
                .param("tableName", "orders").param("columnName", "name")
                .param("ruleType", "TRIM"));
        denied(post("/projects/" + id + "/column-rules/delete").with(asOps("bob")).with(csrf())
                .param("index", "0"));
        denied(post("/projects/" + id + "/cursor").with(asOps("bob")).with(csrf())
                .param("tableName", "orders").param("cursorColumn", "id"));
        denied(post("/projects/" + id + "/delete").with(asOps("bob")).with(csrf()));
    }

    @Test
    void theProjectReadApisRefuseAnotherOperatorsProject() throws Exception {
        Long id = foreignProject("alice").project().getId();

        forbidden(get("/api/projects/" + id + "/preview")
                .param("table", "orders").with(asOps("bob")));
        forbidden(get("/api/projects/" + id + "/columns")
                .param("table", "orders").with(asOps("bob")));
        forbidden(get("/api/projects/" + id + "/cursor-candidates")
                .param("table", "orders").with(asOps("bob")));
        forbidden(get("/api/projects/" + id + "/row-count")
                .param("table", "orders").with(asOps("bob")));
        forbidden(get("/api/projects/" + id + "/precheck").with(asOps("bob")));
        forbidden(get("/api/projects/" + id + "/cdc-probe").with(asOps("bob")));
    }

    @Test
    void theProjectRunApisRefuseAnotherOperatorsProject() throws Exception {
        Long id = foreignProject("alice").project().getId();

        forbidden(post("/api/projects/" + id + "/start").with(asOps("bob")).with(csrf()));
        forbidden(post("/api/projects/" + id + "/stop").with(asOps("bob")).with(csrf()));
        forbidden(post("/api/projects/" + id + "/sync-now").with(asOps("bob")).with(csrf()));
        forbidden(post("/api/projects/" + id + "/reset").with(asOps("bob")).with(csrf()));
        forbidden(post("/api/projects/" + id + "/force-unlock")
                .with(asOps("bob")).with(csrf()));
        forbidden(post("/api/projects/" + id + "/content-audit")
                .param("table", "orders").with(asOps("bob")).with(csrf()));
        forbidden(post("/api/projects/" + id + "/redis-rebuild")
                .with(asOps("bob")).with(csrf()));
    }

    @Test
    void theProjectPerformanceEndpointsRefuseAnotherOperatorsProject() throws Exception {
        Long id = foreignProject("alice").project().getId();
        denied(get("/projects/" + id + "/performance").with(asOps("bob")));
        denied(get("/projects/" + id + "/metrics").with(asOps("bob")));
    }

    // --------------------------------------------------------------------- ETL

    @Test
    void theEtlPagesAndActionsRefuseAnotherOperatorsTask() throws Exception {
        Fixture fixture = foreignProject("alice");
        EtlTask alices = etlTask(unique("alice-etl"), "alice",
                fixture.source().getId(), fixture.target().getId());

        denied(get("/etl/" + alices.getId() + "/edit").with(asOps("bob")));
        denied(post("/etl/precheck").with(asOps("bob")).with(csrf())
                .param("id", alices.getId().toString()));
        denied(post("/etl/" + alices.getId() + "/start").with(asOps("bob")).with(csrf()));
        denied(post("/etl/" + alices.getId() + "/stop").with(asOps("bob")).with(csrf()));
        denied(post("/etl/" + alices.getId() + "/run").with(asOps("bob")).with(csrf()));
        denied(post("/etl/" + alices.getId() + "/delete").with(asOps("bob")).with(csrf()));
    }

    @Test
    void anOpsCanOpenItsOwnEtlTask() throws Exception {
        DatabaseConfig src = connection(unique("bob-etl-src"), "bob", ConnectionRole.SOURCE);
        DatabaseConfig dst = connection(unique("bob-etl-dst"), "bob", ConnectionRole.TARGET);
        EtlTask own = etlTask(unique("bob-etl"), "bob", src.getId(), dst.getId());

        mvc.perform(get("/etl/" + own.getId() + "/edit").with(asOps("bob")))
                .andExpect(status().isOk());
    }

    @Test
    void theEtlBatchActionsRefuseAnotherOperatorsBatch() throws Exception {
        Fixture fixture = foreignProject("alice");
        EtlBatch alices = etlBatch(unique("alice-batch"), "alice",
                fixture.source().getId(), fixture.target().getId());
        EtlTask member = etlTask(unique("alice-member"), "alice",
                fixture.source().getId(), fixture.target().getId());
        member.setBatchId(alices.getId());
        etlTasks.save(member);

        denied(get("/etl/batches/" + alices.getId() + "/edit").with(asOps("bob")));
        denied(post("/etl/batches/" + alices.getId() + "/add-tables")
                .param("tables", "orders").with(asOps("bob")).with(csrf()));
        denied(post("/etl/batches/" + alices.getId() + "/members/"
                + member.getId() + "/remove").with(asOps("bob")).with(csrf()));
        denied(post("/etl/batches/" + alices.getId() + "/run")
                .with(asOps("bob")).with(csrf()));
        denied(post("/etl/batches/" + alices.getId() + "/start")
                .with(asOps("bob")).with(csrf()));
        denied(post("/etl/batches/" + alices.getId() + "/stop")
                .with(asOps("bob")).with(csrf()));
        denied(post("/etl/batches/" + alices.getId() + "/delete")
                .with(asOps("bob")).with(csrf()));
    }

    @Test
    void anOpsCanOpenItsOwnEtlBatch() throws Exception {
        DatabaseConfig src = connection(unique("bob-batch-src"), "bob", ConnectionRole.SOURCE);
        DatabaseConfig dst = connection(unique("bob-batch-dst"), "bob", ConnectionRole.TARGET);
        EtlBatch own = etlBatch(unique("bob-batch"), "bob", src.getId(), dst.getId());

        mvc.perform(get("/etl/batches/" + own.getId() + "/edit").with(asOps("bob")))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- migration

    @Test
    void theMigrationConnectionPagesAndApisRefuseAnotherOperatorsConnection() throws Exception {
        MigrationConnection alices = migrationConnection(unique("alice-search"), "alice");

        denied(get("/migrations/connections/" + alices.getId() + "/edit").with(asOps("bob")));
        denied(post("/migrations/connections/" + alices.getId() + "/delete")
                .with(asOps("bob")).with(csrf()));
        forbidden(post("/api/migrations/connections/" + alices.getId() + "/test")
                .with(asOps("bob")).with(csrf()));
        forbidden(get("/api/migrations/indices").param("connectionId",
                alices.getId().toString()).with(asOps("bob")));
    }

    @Test
    void anOpsCanOpenItsOwnMigrationConnection() throws Exception {
        MigrationConnection own = migrationConnection(unique("bob-search"), "bob");
        mvc.perform(get("/migrations/connections/" + own.getId() + "/edit").with(asOps("bob")))
                .andExpect(status().isOk());
    }

    @Test
    void theMigrationTaskActionsRefuseAnotherOperatorsTask() throws Exception {
        MigrationTaskFixture fixture = foreignMigrationTask("alice");
        String path = "/migrations/es-to-easysearch/" + fixture.task().getId();

        denied(get(path + "/edit").with(asOps("bob")));
        denied(post(path + "/run").with(asOps("bob")).with(csrf()));
        denied(post(path + "/cancel").with(asOps("bob")).with(csrf()));
        denied(post(path + "/enable").with(asOps("bob")).with(csrf()));
        denied(post(path + "/disable").with(asOps("bob")).with(csrf()));
        denied(post(path + "/delete").with(asOps("bob")).with(csrf()));
    }

    @Test
    void anOpsCanOpenItsOwnMigrationTask() throws Exception {
        MigrationTaskFixture fixture = foreignMigrationTask("bob");
        mvc.perform(get("/migrations/es-to-easysearch/" + fixture.task().getId() + "/edit")
                        .with(asOps("bob")))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------- redis

    @Test
    void theRedisTargetPagesRefuseAnotherOperatorsTarget() throws Exception {
        RedisTarget alices = redisTarget(unique("alice-cache"), "alice");
        denied(get("/redis/targets/" + alices.getId() + "/edit").with(asOps("bob")));
        denied(post("/redis/targets/" + alices.getId() + "/delete")
                .with(asOps("bob")).with(csrf()));
    }

    @Test
    void anOpsCanOpenItsOwnRedisTarget() throws Exception {
        RedisTarget own = redisTarget(unique("bob-cache"), "bob");
        mvc.perform(get("/redis/targets/" + own.getId() + "/edit").with(asOps("bob")))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- fixture

    private record Fixture(Project project, DatabaseConfig source, DatabaseConfig target) {
    }

    private record MigrationTaskFixture(MigrationTask task) {
    }

    private Fixture foreignProject(String owner) {
        DatabaseConfig source = connection(unique(owner + "-proj-src"), owner,
                ConnectionRole.SOURCE);
        DatabaseConfig target = connection(unique(owner + "-proj-dst"), owner,
                ConnectionRole.TARGET);
        return new Fixture(project(unique(owner + "-project"), source, target, owner),
                source, target);
    }

    private MigrationTaskFixture foreignMigrationTask(String owner) {
        MigrationConnection source = migrationConnection(unique(owner + "-es"), owner,
                MigrationConnectionKind.ELASTICSEARCH);
        MigrationConnection target = migrationConnection(unique(owner + "-easysearch"), owner,
                MigrationConnectionKind.EASYSEARCH);
        MigrationTask task = new MigrationTask();
        task.setName(unique(owner + "-migration"));
        task.setType(MigrationType.ES_TO_EASYSEARCH);
        task.setSourceConnectionId(source.getId());
        task.setTargetConnectionId(target.getId());
        task.setOptionsJson("{}");
        task.setEnabled(false);
        task.setOwnerUsername(owner);
        return new MigrationTaskFixture(migrationTasks.save(task));
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

    private EtlBatch etlBatch(String name, String owner, Long sourceDbId, Long targetDbId) {
        EtlBatch batch = new EtlBatch();
        batch.setName(name);
        batch.setSourceDbId(sourceDbId);
        batch.setTargetDbId(targetDbId);
        batch.setCronExpression("0 0 3 * * *");
        batch.setEnabled(false);
        batch.setOwnerUsername(owner);
        return etlBatches.save(batch);
    }

    private MigrationConnection migrationConnection(String name, String owner) {
        return migrationConnection(name, owner, MigrationConnectionKind.ELASTICSEARCH);
    }

    private MigrationConnection migrationConnection(String name, String owner,
                                                    MigrationConnectionKind kind) {
        MigrationConnection connection = new MigrationConnection();
        connection.setName(name);
        connection.setKind(kind);
        connection.setBaseUrl("http://127.0.0.1:9200");
        connection.setAuthType(MigrationConnectionAuthType.NONE);
        connection.setVerifyTls(true);
        connection.setConnectTimeoutMs(5000);
        connection.setRequestTimeoutMs(60000);
        connection.setOwnerUsername(owner);
        return migrationConnections.save(connection);
    }

    private RedisTarget redisTarget(String name, String owner) {
        RedisTarget target = new RedisTarget();
        target.setName(name);
        target.setHost("127.0.0.1");
        target.setPort(6379);
        target.setOwnerUsername(owner);
        return redisTargets.save(target);
    }

    private static String unique(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    private static AccessGuard guardOf(String username) {
        return AccessGuard.of(username, "admin".equals(username) ? UserRole.ADMIN : UserRole.OPS);
    }

    // ---------------------------------------------------------------- requests

    private void denied(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?denied"));
    }

    private void forbidden(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isForbidden());
    }

    private static RequestPostProcessor asOps(String username) {
        return as(username, UserRole.OPS);
    }

    private static RequestPostProcessor asAdmin() {
        return as("admin", UserRole.ADMIN);
    }

    private static RequestPostProcessor asViewer() {
        return as("viewer", UserRole.VIEWER);
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
