package com.qqmu.jync.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.etl.EtlBatchService;
import com.qqmu.jync.service.metadata.ConnectionCatalogService;

/**
 * The write side of the ownership rule, against the real H2 metadata database.
 *
 * <p>The filter chain decides whether a role may write at all; this class pins the second
 * question — whether this caller may touch <em>this</em> row. An operator edits only its own
 * resources, an administrator edits everything, and an owner column never comes from the form:
 * a crafted POST that binds or blanks it must not move a resource to another account.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.github-url=https://example.com/repo",
        "app.update-check.enabled=false",
        "sync.ai.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ownerwrite;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.quartz.job-store-type=memory",
        "logging.file.name=target/ownerwrite-logs/jync.log"
})
class OwnerWriteRulesTest {

    @Autowired private DatabaseConfigService databaseConfigs;
    @Autowired private ProjectService projects;
    @Autowired private EtlBatchService etlBatches;
    @Autowired private EtlBatchRepository etlBatchRepository;
    @Autowired private EtlTaskRepository etlTaskRepository;
    /** The batch-creation path re-reads the source catalog; stub it instead of opening a DB. */
    @MockitoBean private ConnectionCatalogService catalogService;

    private static final AccessGuard ALICE = AccessGuard.of("alice", UserRole.OPS);
    private static final AccessGuard BOB = AccessGuard.of("bob", UserRole.OPS);
    private static final AccessGuard ADMIN = AccessGuard.of("admin", UserRole.ADMIN);

    @Test
    void aNewConnectionBelongsToItsCreator() {
        DatabaseConfig saved = databaseConfigs.save(connection("c1"), "pw", null, ALICE);
        assertThat(saved.getOwnerUsername()).isEqualTo("alice");
    }

    @Test
    void anEditKeepsTheStoredOwnerAndIgnoresTheSubmittedOne() {
        DatabaseConfig saved = databaseConfigs.save(connection("c2"), "pw", null, ALICE);
        saved.setOwnerUsername("bob");          // a crafted POST binding the owner
        DatabaseConfig updated = databaseConfigs.save(saved, "pw", null, ALICE);
        assertThat(updated.getOwnerUsername()).isEqualTo("alice");
    }

    @Test
    void anotherOperatorCannotEditOrDeleteTheConnection() {
        DatabaseConfig saved = databaseConfigs.save(connection("c3"), "pw", null, ALICE);
        assertThatThrownBy(() -> databaseConfigs.save(saved, "pw", null, BOB))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> databaseConfigs.delete(saved.getId(), BOB))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(databaseConfigs.findById(saved.getId())).isPresent();
    }

    @Test
    void aProjectFollowsTheSameRules() {
        DatabaseConfig source = databaseConfigs.save(connection("p-src"), "pw", null, ALICE);
        DatabaseConfig target = databaseConfigs.save(targetConnection("p-dst"), "pw", null, ALICE);
        Project project = new Project();
        project.setName("p1");
        project.setSourceDbId(source.getId());
        project.setTargetDbId(target.getId());
        Project saved = projects.save(project, ALICE);
        assertThat(saved.getOwnerUsername()).isEqualTo("alice");

        saved.setOwnerUsername(null);           // a crafted POST trying to blank it
        assertThat(projects.save(saved, ALICE).getOwnerUsername()).isEqualTo("alice");
        assertThat(projects.requireEditable(saved.getId(), ADMIN).getOwnerUsername())
                .isEqualTo("alice");
        assertThatThrownBy(() -> projects.requireEditable(saved.getId(), BOB))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anOperatorCannotReferenceAnotherOperatorsConnection() {
        DatabaseConfig bobDb = databaseConfigs.save(connection("bobs-db"), "pw", null, BOB);
        Project project = new Project();
        project.setName("ref-1");
        project.setSourceDbId(bobDb.getId());

        assertThatThrownBy(() -> projects.save(project, ALICE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ownership.reference:bobs-db");
    }

    @Test
    void theSystemOwnerIsNotReferenceableByAnOperator() {
        DatabaseConfig legacy = databaseConfigs.save(connection("legacy-db"), "pw", null,
                AccessGuard.system());
        assertThat(legacy.getOwnerUsername()).isNull();
        Project project = new Project();
        project.setName("ref-2");
        project.setSourceDbId(legacy.getId());

        assertThatThrownBy(() -> projects.save(project, ALICE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.ownership.reference:legacy-db");
    }

    @Test
    void anAdminMayReferenceAnything() {
        DatabaseConfig bobDb = databaseConfigs.save(connection("bobs-db-2"), "pw", null, BOB);
        DatabaseConfig aliceDb = databaseConfigs.save(targetConnection("alices-db"), "pw", null,
                ALICE);
        Project project = new Project();
        project.setName("ref-3");
        project.setSourceDbId(bobDb.getId());
        project.setTargetDbId(aliceDb.getId());

        assertThat(projects.save(project, ADMIN).getOwnerUsername()).isEqualTo("admin");
    }

    @Test
    void aNewBatchAndItsMembersInheritTheCreator() {
        DatabaseConfig source = databaseConfigs.save(connection("b-src"), "pw", null, ALICE);
        DatabaseConfig target = databaseConfigs.save(targetConnection("b-dst"), "pw", null, ALICE);
        when(catalogService.listTables(source.getId()))
                .thenReturn(new ConnectionCatalogService.Tables("public", List.of("orders")));

        EtlBatch form = new EtlBatch();
        form.setName("nightly");
        form.setSourceDbId(source.getId());
        form.setTargetDbId(target.getId());
        etlBatches.createWithTables(form, List.of("orders"), ALICE);

        EtlBatch batch = etlBatchRepository.findAll().get(0);
        assertThat(batch.getOwnerUsername()).isEqualTo("alice");
        assertThat(etlTaskRepository.findByBatchIdOrderByIdAsc(batch.getId()))
                .singleElement().extracting(EtlTask::getOwnerUsername).isEqualTo("alice");
    }

    @Test
    void aBatchEditIsRefusedForAnotherOperator() {
        DatabaseConfig source = databaseConfigs.save(connection("e-src"), "pw", null, ALICE);
        DatabaseConfig target = databaseConfigs.save(targetConnection("e-dst"), "pw", null, ALICE);
        EtlBatch form = new EtlBatch();
        form.setName("owned");
        form.setSourceDbId(source.getId());
        form.setTargetDbId(target.getId());
        EtlBatch batch = etlBatches.saveSettings(form, ALICE);   // id == null → create branch
        assertThat(batch.getOwnerUsername()).isEqualTo("alice");

        EtlBatch edit = new EtlBatch();
        edit.setId(batch.getId());
        edit.setName("renamed");
        edit.setOwnerUsername("bob");                          // crafted POST binding
        assertThatThrownBy(() -> etlBatches.saveSettings(edit, BOB))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(etlBatches.saveSettings(edit, ALICE).getOwnerUsername()).isEqualTo("alice");
    }

    @Test
    void aNewBatchMustNotReferenceAnotherOperatorsConnections() {
        DatabaseConfig aliceSource = databaseConfigs.save(connection("s-src"), "pw", null, ALICE);
        DatabaseConfig aliceTarget = databaseConfigs.save(targetConnection("s-dst"), "pw", null,
                ALICE);
        String name = "borrowed-" + System.nanoTime();

        EtlBatch form = new EtlBatch();
        form.setName(name);
        form.setSourceDbId(aliceSource.getId());
        form.setTargetDbId(aliceTarget.getId());

        // The /etl/batches/save create branch is a second way into a new batch. If it skipped
        // the reference rule, an operator could bind a batch he owns to her connections, then
        // add tables and run it — reading her source and writing her target with her secrets.
        assertThatThrownBy(() -> etlBatches.saveSettings(form, BOB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("error.ownership.reference");
        assertThat(etlBatchRepository.findAll())
                .noneMatch(batch -> name.equals(batch.getName()));
    }

    /** The smallest connection the service accepts: MySQL, SOURCE, TCP, one database. */
    private static DatabaseConfig connection(String name) {
        return connection(name, ConnectionRole.SOURCE);
    }

    private static DatabaseConfig targetConnection(String name) {
        return connection(name, ConnectionRole.TARGET);
    }

    private static DatabaseConfig connection(String name, ConnectionRole role) {
        DatabaseConfig config = new DatabaseConfig();
        config.setName(name);
        config.setType(DatabaseType.MYSQL);
        config.setRole(role);
        config.setHost("127.0.0.1");
        config.setPort(3306);
        // Distinct physical databases: the ETL guard refuses a task whose source and target
        // are the same endpoint, and these fixtures exist to test ownership, not that.
        config.setDatabaseName(name);
        config.setUsername("sync");
        return config;
    }
}
