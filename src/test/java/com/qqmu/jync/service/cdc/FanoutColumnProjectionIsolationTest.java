package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * Two fan-out targets of the same type and schema share one {@link CdcRuntimeStats}. The
 * column-projection cache must therefore be keyed by the target connection: target A missing
 * a column switches projection on, and target B — which has the column — must not inherit
 * A's "this column does not exist" answer and silently drop the value.
 */
class FanoutColumnProjectionIsolationTest {

    private static TableMeta orders() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        ColumnMeta name = new ColumnMeta();
        name.setName("name");
        name.setJdbcType(Types.VARCHAR);
        ColumnMeta note = new ColumnMeta();
        note.setName("note");
        note.setJdbcType(Types.VARCHAR);
        meta.getColumns().add(id);
        meta.getColumns().add(name);
        meta.getColumns().add(note);
        meta.getPrimaryKeys().add("id");
        return meta;
    }

    private static SyncContext ctx(long connectionId) {
        DatabaseConfig target = new DatabaseConfig();
        target.setId(connectionId);
        target.setName("t" + connectionId);
        target.setType(DatabaseType.H2);
        return SyncContext.builder()
                .config(new SyncConfig())
                .targetConfig(target)
                .targetType(DatabaseType.H2)
                .targetDialect(new GenericSqlDialect())
                .batchSize(10)
                .build();
    }

    @Test
    void aTargetWithoutTheColumnDoesNotProjectItAwayForATargetThatHasIt() throws Exception {
        try (Connection behind = DriverManager.getConnection(
                        "jdbc:h2:mem:fanout_proj_a;DB_CLOSE_DELAY=-1", "sa", "");
             Connection healthy = DriverManager.getConnection(
                        "jdbc:h2:mem:fanout_proj_b;DB_CLOSE_DELAY=-1", "sa", "");
             Statement a = behind.createStatement();
             Statement b = healthy.createStatement()) {
            // Quoted so the dictionary sees the same lowercase names the binding carries.
            a.execute("CREATE TABLE \"orders\" (\"id\" BIGINT PRIMARY KEY,"
                    + " \"name\" VARCHAR(64))");
            b.execute("CREATE TABLE \"orders\" (\"id\" BIGINT PRIMARY KEY,"
                    + " \"name\" VARCHAR(64), \"note\" VARCHAR(64))");

            CdcRuntimeStats shared = new CdcRuntimeStats();
            // A skipped DDL switched the projection on for the whole project.
            shared.addSkippedDdl(1);
            CdcTableBinding binding = CdcTableBinding.of("src", "orders", orders(), "orders",
                    null);
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", 1L);
            row.put("name", "ann");
            row.put("note", "keep-me");

            new CdcEventApplier(ctx(1L), shared).applyUpserts(behind, binding, List.of(row));
            new CdcEventApplier(ctx(2L), shared).applyUpserts(healthy, binding, List.of(row));

            try (ResultSet rs = b.executeQuery("SELECT \"note\" FROM \"orders\" WHERE \"id\" = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("keep-me");
            }
            // The behind target still converges on the columns it does have.
            try (ResultSet rs = a.executeQuery("SELECT \"name\" FROM \"orders\" WHERE \"id\" = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("ann");
            }
        }
    }
}
