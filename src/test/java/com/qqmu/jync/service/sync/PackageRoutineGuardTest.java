package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.GenericSqlDialect;

/**
 * A routine whose name still carries an Oracle package prefix ("PKG.PROC" — configs saved
 * before package members were excluded from selection) must fail with an actionable message
 * rather than ending in an empty-body skip.
 */
class PackageRoutineGuardTest {

    private Connection conn;
    private StructureSyncService structure;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:h2:mem:pkg_guard;DB_CLOSE_DELAY=-1", "sa", "");
        structure = new StructureSyncService(
                new com.qqmu.jync.service.converter.SqlBodyConverter());

        Project project = new Project();
        project.setId(9L);
        project.setName("P");
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    void aDottedRoutineNameFailsWithGuidance() {
        ProcedureMeta proc = new ProcedureMeta();
        proc.setName("APP_PKG.DO_WORK");
        proc.setRoutineType("PROCEDURE");
        proc.setDefinition("BEGIN NULL; END");
        ChangeEvent event = ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.CREATE,
                "APP_PKG.DO_WORK", "routine create", proc);

        StructureSyncService.ApplyOutcome outcome = structure.apply(conn, event, ctx);

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("member of an Oracle package");
        assertThat(outcome.error).contains("DDL override");
    }

    @Test
    void aStandaloneRoutineIsNotBlockedByTheGuard() {
        ProcedureMeta proc = new ProcedureMeta();
        proc.setName("DO_WORK");
        proc.setRoutineType("PROCEDURE");
        // H2 executes this as a routine body even though it is not a real procedure statement;
        // the guard check happens before conversion and must not flag the plain name. We only
        // assert the failure (if any) is NOT the package guard.
        proc.setDefinition("CALL 1");
        ChangeEvent event = ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.CREATE,
                "DO_WORK", "routine create", proc);

        StructureSyncService.ApplyOutcome outcome = structure.apply(conn, event, ctx);

        assertThat(outcome.error == null || !outcome.error.contains("Oracle package"))
                .isTrue();
    }
}
