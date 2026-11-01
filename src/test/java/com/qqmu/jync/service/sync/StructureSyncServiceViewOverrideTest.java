package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ViewMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.Db2Dialect;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.SqlBodyConverter;
import com.qqmu.jync.service.converter.SqlServerDialect;

/**
 * A saved view override runs verbatim, so it carries the same hazards as the routine path:
 * a schema prefix can write to another schema, and a dialect whose generated view DDL is a
 * DROP+CREATE needs that DROP before a pasted CREATE VIEW can replace an existing view.
 */
class StructureSyncServiceViewOverrideTest {

    private Connection target;
    private StructureSyncService structure;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:view_override;DB_CLOSE_DELAY=-1", "sa", "");
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
                .targetSchema("cee")
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

    private ChangeEvent event() {
        ViewMeta view = new ViewMeta();
        view.setName("V_X");
        view.setDefinition("SELECT 1");
        return ChangeEvent.of(ObjectType.VIEW, ChangeType.CREATE, "V_X", "view create", view);
    }

    @Test
    @DisplayName("a legacy view override naming another schema is refused, never executed")
    void anOverrideNamingAnotherSchemaIsNotExecuted() {
        // Overrides saved before the save-time schema check can still carry a source schema.
        // Running one writes to that schema on the target server, so refuse it and say both
        // names — the driver's "already exists" is what the operator could not decode.
        SyncConfig config = new SyncConfig();
        config.getDdlOverrides().put("VIEW:V_X",
                "CREATE VIEW `test-rgh`.`V_X` AS SELECT 1");

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(target, event(), mysqlContext(config, "cee"));

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("test-rgh").contains("cee");
        assertThat(outcome.error).doesNotContain("already exists");
    }

    @Test
    @DisplayName("a SQL Server view override drops the existing view first, so a retry can win")
    void aSqlServerViewOverrideDropsTheExistingViewFirst() {
        String override = "CREATE VIEW [dbo].[V_X] AS SELECT 1";

        List<String> statements = StructureSyncService.viewOverrideStatements(
                new SqlServerDialect(), override, "dbo", view());

        assertThat(statements).containsExactly(
                "DROP VIEW [dbo].[V_X]", override);
    }

    @Test
    @DisplayName("a DB2 view override drops the existing view first, so a retry can win")
    void aDb2ViewOverrideDropsTheExistingViewFirst() {
        String override = "CREATE VIEW \"DB2INST1\".\"V_X\" AS SELECT 1";

        List<String> statements = StructureSyncService.viewOverrideStatements(
                new Db2Dialect(), override, "DB2INST1", view());

        assertThat(statements).containsExactly(
                "DROP VIEW \"DB2INST1\".\"V_X\"", override);
    }

    @Test
    @DisplayName("a plain CREATE VIEW override still drops the existing view first")
    void aPlainCreateViewOverrideDropsFirst() {
        // "OR REPLACE" is what lets a dialect replace in place. A pasted plain CREATE VIEW on a
        // dialect that normally replaces (MySQL, Oracle) fails with "already exists" on every
        // cycle once the view is there, so the stored override never converges.
        String override = "CREATE VIEW `V_X` AS SELECT 1";

        List<String> statements = StructureSyncService.viewOverrideStatements(
                new MySqlDialect(), override, "cee", view());

        assertThat(statements).containsExactly("DROP VIEW `cee`.`V_X`", override);
    }

    @Test
    @DisplayName("a dialect whose CREATE OR REPLACE replaces views runs the override alone")
    void aReplacingDialectLeavesTheViewOverrideAlone() {
        String override = "CREATE OR REPLACE VIEW `V_X` AS SELECT 1;";

        List<String> statements = StructureSyncService.viewOverrideStatements(
                new MySqlDialect(), override, "cee", view());

        // The terminator is still stripped: a driver expects the statement, not a script.
        assertThat(statements).containsExactly("CREATE OR REPLACE VIEW `V_X` AS SELECT 1");
    }

    @Test
    @DisplayName("the preparatory drop targets the event's view, never the header's name")
    void theDropUsesTheEventNameNotTheOverrideHeader() {
        // A header naming another view used to steer the drop: V_X's override dropped V_Y and
        // then reported V_X as created. The apply path refuses the mismatch; the drop helper
        // must not reintroduce it if that check is ever bypassed.
        List<String> statements = StructureSyncService.viewOverrideStatements(
                new SqlServerDialect(), "CREATE VIEW [dbo].[Other] AS SELECT 1", "dbo", view());

        assertThat(statements.get(0)).isEqualTo("DROP VIEW [dbo].[V_X]");
    }

    @Test
    @DisplayName("an apply refuses an override that names another object")
    void anOverrideNamingAnotherObjectIsRefused() {
        SyncConfig config = new SyncConfig();
        config.getDdlOverrides().put("VIEW:V_X", "CREATE VIEW \"cee\".\"Other\" AS SELECT 1");

        StructureSyncService.ApplyOutcome outcome =
                structure.apply(target, event(), context(config));

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.error).contains("V_X").contains("Other");
        // Nothing ran: a refusal that still executed the statement would delete "Other".
        assertThat(outcome.error).doesNotContain("already exists");
    }

    private static ViewMeta view() {
        ViewMeta view = new ViewMeta();
        view.setName("V_X");
        view.setDefinition("SELECT 1");
        return view;
    }
}
