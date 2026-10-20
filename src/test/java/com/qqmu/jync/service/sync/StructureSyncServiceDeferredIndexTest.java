package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ChangeEvent;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.IndexMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.converter.GenericSqlDialect;

/**
 * Deferred secondary indexes: maintaining them row by row while a freshly created table takes
 * its initial load roughly doubled the load in the Oracle benchmark (11.1s vs 5.9s for 500k
 * rows), while building both afterwards took 0.5s. Non-unique indexes are therefore held back
 * until the load completes; the primary key (inside CREATE TABLE) and unique indexes stay
 * immediate, because the upsert needs the PK and a unique index must catch duplicate source
 * rows as they arrive rather than at the end.
 */
class StructureSyncServiceDeferredIndexTest {

    private Connection target;
    private StructureSyncService structure;
    private SyncContext ctx;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection("jdbc:h2:mem:defer_idx;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = target.createStatement()) {
            st.execute("DROP TABLE IF EXISTS T");
        }
        structure = new StructureSyncService(new com.qqmu.jync.service.converter.SqlBodyConverter());

        Project project = new Project();
        project.setId(21L);
        project.setName("P");
        ctx = SyncContext.builder()
                .project(project)
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .deferSecondaryIndexes(true)
                .build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        target.close();
    }

    @Test
    void uniqueIndexesAreCreatedImmediatelyWhileNonUniqueOnesWait() throws SQLException {
        TableMeta table = sourceTable();

        StructureSyncService.ApplyOutcome outcome = structure.apply(target, createEvent(table), ctx);

        assertThat(outcome.applied).isTrue();
        // Uniqueness must hold from the first row, so this one is not deferred.
        assertThat(indexExists("UK_CODE")).isTrue();
        // The plain lookup index is pure write cost during the load.
        assertThat(indexExists("IDX_NAME")).isFalse();
        assertThat(outcome.detail).contains("deferred");
    }

    @Test
    void deferredIndexesAreBuiltWhenTheInitialLoadCompletes() throws SQLException {
        TableMeta table = sourceTable();
        structure.apply(target, createEvent(table), ctx);

        assertThat(structure.createDeferredIndexes(target, ctx, table)).isEqualTo(1);
        assertThat(indexExists("IDX_NAME")).isTrue();

        // A resumed load or a re-run calls this again; "already there" is the desired end state.
        assertThat(structure.createDeferredIndexes(target, ctx, table)).isZero();
    }

    @Test
    void structureOnlyProjectsKeepCreatingEveryIndexUpFront() throws SQLException {
        // No data load follows, so deferring would leave the target without its indexes.
        ctx.getConfig().setSyncData(false);

        structure.apply(target, createEvent(sourceTable()), ctx);

        assertThat(indexExists("IDX_NAME")).isTrue();
        assertThat(indexExists("UK_CODE")).isTrue();
    }

    private ChangeEvent createEvent(TableMeta table) {
        return ChangeEvent.of(ObjectType.TABLE, ChangeType.CREATE, "T", "table create", table);
    }

    private static TableMeta sourceTable() {
        TableMeta table = new TableMeta();
        table.setName("T");
        table.setColumns(List.of(
                col("ID", Types.BIGINT), col("NAME", Types.VARCHAR), col("CODE", Types.VARCHAR)));

        IndexMeta nameIndex = new IndexMeta();
        nameIndex.setName("IDX_NAME");
        nameIndex.setColumns(List.of("NAME"));

        IndexMeta codeIndex = new IndexMeta();
        codeIndex.setName("UK_CODE");
        codeIndex.setUnique(true);
        codeIndex.setColumns(List.of("CODE"));

        table.setIndexes(List.of(nameIndex, codeIndex));
        return table;
    }

    private static ColumnMeta col(String name, int jdbcType) {
        ColumnMeta column = new ColumnMeta();
        column.setName(name);
        column.setJdbcType(jdbcType);
        column.setSize(100);
        return column;
    }

    private boolean indexExists(String indexName) throws SQLException {
        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = '"
                             + indexName + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }
}
