package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ProcedureMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.MySqlDialect;
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

    private SyncContext mysqlContext(SyncConfig config, String targetSchema) {
        Project project = new Project();
        project.setId(12L);
        project.setName("P");
        return SyncContext.builder()
                .project(project)
                .config(config)
                .targetDialect(new MySqlDialect())
                .targetSchema(targetSchema)
                .batchSize(50)
                .build();
    }

    private ChangeEvent event(String definition) {
        return event(procedure(definition));
    }

    private ChangeEvent event(ProcedureMeta proc) {
        return ChangeEvent.of(ObjectType.PROCEDURE, ChangeType.CREATE, "InsertTestData",
                "routine create", proc);
    }

    private ProcedureMeta procedure(String definition) {
        ProcedureMeta proc = new ProcedureMeta();
        proc.setName("InsertTestData");
        proc.setRoutineType("PROCEDURE");
        proc.setDefinition(definition);
        return proc;
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

    @Test
    @DisplayName("a legacy override naming another schema is refused, never executed")
    void anOverrideNamingAnotherSchemaIsNotExecuted() {
        // Overrides saved before the schema check is enforced can still carry a source schema.
        // Running one writes to that schema on the target server — possibly the source schema
        // itself when both ends share a server — so refuse it and say both names.
        SyncConfig config = new SyncConfig();
        config.getDdlOverrides().put("PROCEDURE:InsertTestData",
                "CREATE PROCEDURE `test-rgh`.`InsertTestData`() BEGIN END");

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(target, event("BEGIN NULL; END"), mysqlContext(config, "cee"));

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("test-rgh").contains("cee");
        // The MySQL driver's "already exists" is what the operator could not decode.
        assertThat(outcome.error).doesNotContain("already exists");
    }

    @Test
    @DisplayName("a MySQL routine override drops the target routine first, so a retry can win")
    void aMysqlRoutineOverrideDropsTheExistingRoutineFirst() {
        String override = "CREATE PROCEDURE `InsertTestData`() BEGIN END";

        List<String> statements = StructureSyncService.routineOverrideStatements(
                new MySqlDialect(), override, "cee", procedure("BEGIN NULL; END"));

        assertThat(statements).containsExactly(
                "DROP PROCEDURE `cee`.`InsertTestData`", override);
    }

    @Test
    @DisplayName("the preparatory drop targets the event's routine, never the header's name")
    void theDropUsesTheEventNameNotTheOverrideHeader() {
        // A mismatched header used to steer the drop, so saving X's override could delete Y and
        // still report X as created. Apply now refuses the mismatch; the drop helper agrees.
        List<String> statements = StructureSyncService.routineOverrideStatements(
                new MySqlDialect(), "CREATE PROCEDURE `Calc`() BEGIN END", "cee",
                procedure("BEGIN NULL; END"));

        assertThat(statements.get(0)).isEqualTo("DROP PROCEDURE `cee`.`InsertTestData`");
    }

    @Test
    @DisplayName("an apply refuses an override that names another routine")
    void anOverrideNamingAnotherRoutineIsRefused() {
        SyncConfig config = new SyncConfig();
        config.getDdlOverrides().put("PROCEDURE:InsertTestData",
                "CREATE PROCEDURE `cee`.`Other`() BEGIN END");

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(target, event("BEGIN NULL; END"), mysqlContext(config, "cee"));

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("InsertTestData").contains("Other");
        assertThat(outcome.error).doesNotContain("already exists");
    }

    @Test
    @DisplayName("a trailing SQL*Plus slash never reaches the driver")
    void aTrailingSlashIsStrippedFromTheOverride() {
        // SQL Developer writes PL/SQL as the block, then a lone "/" line. That line is a client
        // command, not SQL: Oracle answers ORA-00911 and the override fails on every cycle. The
        // block's own END; must survive — stripping trailing semicolons like a view would break it.
        String override = "CREATE OR REPLACE PROCEDURE p AS BEGIN NULL; END;\n/\n";

        List<String> statements = StructureSyncService.routineOverrideStatements(
                new GenericSqlDialect(), override, "cee", procedure("BEGIN NULL; END"));

        assertThat(statements)
                .containsExactly("CREATE OR REPLACE PROCEDURE p AS BEGIN NULL; END;");
    }

    @Test
    @DisplayName("a dialect whose CREATE replaces routines runs the override alone")
    void aReplacingDialectLeavesTheOverrideAlone() {
        String override = "CREATE OR REPLACE PROCEDURE `InsertTestData`() BEGIN END";

        List<String> statements = StructureSyncService.routineOverrideStatements(
                new GenericSqlDialect(), override, "cee", procedure("BEGIN NULL; END"));

        assertThat(statements).containsExactly(override);
    }

    @Test
    @DisplayName("a plain CREATE routine override still drops the existing routine first")
    void aPlainCreateRoutineOverrideDropsFirst() {
        // Same hole as the view path: without OR REPLACE the CREATE fails with "already exists"
        // on every cycle once the routine exists, so the override never converges.
        String override = "CREATE PROCEDURE `InsertTestData`() BEGIN END";

        List<String> statements = StructureSyncService.routineOverrideStatements(
                new GenericSqlDialect(), override, "cee", procedure("BEGIN NULL; END"));

        assertThat(statements.get(1)).isEqualTo(override);
        assertThat(statements.get(0).toUpperCase(Locale.ROOT)).contains("DROP PROCEDURE");
    }
}
