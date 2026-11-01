package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

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
import com.qqmu.jync.service.converter.SqlBodyConverter;

/**
 * A routine failure must say which path failed.
 *
 * <p>When the project carries a saved DDL override, structure sync runs that statement verbatim
 * instead of generating one. Reporting "automatic conversion failed" then misdirects the user:
 * there was no conversion, and the fix is to correct or clear the override they wrote. The
 * auto path keeps its existing message, which asks for an override.
 */
class StructureSyncServiceProcedureOverrideTest {

    private Connection target;
    private StructureSyncService structure;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:proc_override;DB_CLOSE_DELAY=-1", "sa", "");
        structure = new StructureSyncService(new SqlBodyConverter());
    }

    @AfterEach
    void tearDown() throws SQLException {
        target.close();
    }

    private SyncContext context(SyncConfig config) {
        Project project = new Project();
        project.setId(12L);
        project.setName("P");
        return SyncContext.builder()
                .project(project)
                .config(config)
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }

    private ChangeEvent event(String definition) {
        ProcedureMeta proc = new ProcedureMeta();
        proc.setName("InsertTestData");
        proc.setRoutineType("PROCEDURE");
        proc.setDefinition(definition);
        return ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.CREATE, "InsertTestData",
                "routine create", proc);
    }

    @Test
    void aFailedOverrideIsReportedAsAnOverrideFailure() {
        SyncConfig config = new SyncConfig();
        config.getDdlOverrides().put("PROCEDURE:InsertTestData", "THIS IS NOT VALID SQL");

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(target, event("BEGIN NULL; END"), context(config));

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("DDL override for PROCEDURE InsertTestData failed");
        // The override ran verbatim; nothing was converted, so the user must not be told to
        // debug a conversion that never happened.
        assertThat(outcome.error).doesNotContain("Automatic conversion");
    }

    @Test
    void aFailedAutomaticConversionStillAsksForAnOverride() {
        StructureSyncService.ApplyOutcome outcome =
                structure.apply(target, event("THIS IS NOT VALID SQL"), context(new SyncConfig()));

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).startsWith("Automatic conversion of PROCEDURE InsertTestData");
        // No override was saved, so the right advice is still "provide one".
        assertThat(outcome.error).contains("Provide a manual DDL override");
    }
}
