package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * The JDBC half of the target-transaction seam: one borrowed connection, upserts before
 * deletes, one commit, and a close that always returns the connection to the pool. These
 * are the exact obligations the five CDC consumers used to implement each for themselves.
 */
class JdbcCdcTargetTransactionTest {

    private static final String URL =
            "jdbc:h2:mem:cdc_target_tx;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    private Connection target;
    private SyncContext ctx;
    private CdcRuntimeStats stats;
    private CdcTableBinding orders;

    @BeforeEach
    void setUp() throws SQLException {
        target = open();
        try (Statement st = target.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        }
        ctx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(2)
                .connectionProvider(config -> target)
                .build();
        stats = new CdcRuntimeStats();
        orders = CdcTableBinding.of("src", "orders", meta("orders"), "orders", null);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(URL, "sa", "");
    }

    private static TableMeta meta(String name) {
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        ColumnMeta label = new ColumnMeta();
        label.setName("name");
        label.setJdbcType(Types.VARCHAR);
        TableMeta meta = new TableMeta();
        meta.setName(name);
        meta.setColumns(List.of(id, label));
        meta.setPrimaryKeys(List.of("id"));
        return meta;
    }

    private static Map<String, Object> row(long id, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        return row;
    }

    private List<Map<String, Object>> rows() throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM orders ORDER BY id")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getLong("id"));
                row.put("name", rs.getString("name"));
                out.add(row);
            }
        }
        return out;
    }

    @Test
    void jdbcTransactionCommitsThroughTheExistingApplier() throws Exception {
        try (JdbcCdcTargetTransaction tx =
                     new JdbcCdcTargetTransaction(ctx, stats, 0)) {
            tx.applyUpserts(orders, List.of(row(1L, "a"), row(2L, "b")));
            // Upserts must land before deletes: deleting 1 after inserting it leaves only 2.
            // A delete-first order would have re-inserted row 1.
            tx.applyDeletes(orders, List.of(row(1L, null)));
            tx.commit();
        }

        assertThat(rows()).containsExactly(row(2L, "b"));
        assertThat(target.isClosed()).isTrue();
    }

    @Test
    void aFailedApplyRollsBackAndCloses() throws Exception {
        CdcTableBinding missing = CdcTableBinding.of("src", "ghost",
                meta("ghost"), "no_such_table", null);

        try (JdbcCdcTargetTransaction tx =
                     new JdbcCdcTargetTransaction(ctx, stats, 0)) {
            tx.applyUpserts(orders, List.of(row(1L, "a")));
            assertThatThrownBy(() -> tx.applyUpserts(missing, List.of(row(1L, "a"))))
                    .isInstanceOf(SQLException.class);
        }

        assertThat(rows()).isEmpty();
        assertThat(target.isClosed()).isTrue();
    }

    @Test
    void aCommittedTransactionIsNotRolledBackOnClose() throws Exception {
        JdbcCdcTargetTransaction tx = new JdbcCdcTargetTransaction(ctx, stats, 0);
        tx.applyUpserts(orders, List.of(row(7L, "kept")));
        tx.commit();
        tx.close();

        assertThat(rows()).containsExactly(row(7L, "kept"));
        assertThat(target.isClosed()).isTrue();
    }
}
