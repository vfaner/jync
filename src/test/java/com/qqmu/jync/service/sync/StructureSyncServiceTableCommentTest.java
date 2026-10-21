package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlBodyConverter;

/**
 * A comment edited at the source must reach a table that already exists. The detector emits
 * a TABLE ALTER for it; this proves the apply half actually changes the target instead of
 * re-issuing a CREATE that is a no-op on an existing table.
 */
class StructureSyncServiceTableCommentTest {

    private Connection target;
    private StructureSyncService structure;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection("jdbc:h2:mem:table_comment;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = target.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T");
            st.execute("CREATE TABLE T (ID BIGINT)");
        }
        structure = new StructureSyncService(new SqlBodyConverter());

        Project project = new Project();
        project.setId(12L);
        project.setName("P");
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .targetDialect(new CommentDialect())
                .batchSize(50)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        target.close();
    }

    @Test
    void anExistingTablesCommentIsUpdatedToTheSources() throws SQLException {
        TableMeta table = table("订单主表");

        StructureSyncService.ApplyOutcome outcome = structure.apply(target,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.ALTER, "T", "comment changed", table),
                ctx);

        assertThat(outcome.applied).isTrue();
        assertThat(targetComment()).isEqualTo("订单主表");
    }

    @Test
    void aCommentRemovedAtTheSourceIsClearedOnTheTarget() throws SQLException {
        structure.apply(target,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.ALTER, "T", "set", table("旧注释")), ctx);

        structure.apply(target,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.ALTER, "T", "cleared", table(null)), ctx);

        // H2 reports an emptied comment as NULL; both mean the old text is gone.
        assertThat(targetComment()).isNullOrEmpty();
    }

    @Test
    void aFailingCommentUpdateDoesNotBlockTheTable() throws SQLException {
        SyncContext failing = SyncContext.builder()
                .project(ctx.getProject())
                .config(ctx.getConfig())
                .targetDialect(new CommentDialect() {
                    @Override
                    public String getAlterTableCommentSql(String schema, String table,
                                                          String comment) {
                        return "COMMENT ON TABLE NO_SUCH_TABLE IS 'x'";
                    }
                })
                .batchSize(50)
                .build();

        // Comments are cosmetic: withholding the snapshot over one would retry the same
        // failing ALTER forever and could keep data sync blocked behind it.
        StructureSyncService.ApplyOutcome outcome = structure.apply(target,
                ChangeEvent.of(ObjectType.TABLE, ChangeType.ALTER, "T", "comment", table("x")),
                failing);

        assertThat(outcome.applied).isTrue();
        assertThat(outcome.error).isNull();
    }

    private String targetComment() throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT REMARKS FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'T'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static TableMeta table(String comment) {
        TableMeta table = new TableMeta();
        table.setName("T");
        table.setRemarks(comment);
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setTypeName("BIGINT");
        id.setJdbcType(Types.BIGINT);
        id.setNullable(true);
        table.getColumns().add(id);
        return table;
    }

    /** H2 has no MySQL-style ALTER, so the test dialect maps a comment to its own syntax. */
    private static class CommentDialect extends GenericSqlDialect {
        @Override
        public String getAlterTableCommentSql(String schema, String table, String comment) {
            return "COMMENT ON TABLE " + table + " IS '"
                    + (comment == null ? "" : comment.replace("'", "''")) + "'";
        }
    }
}
