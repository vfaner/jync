package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.lob.LobSpillSession;
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.lob.LobValue;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * Ownership across the applier boundary: the handler's transaction buffer owns spilled
 * values and releases them at the transaction boundary. The applier only binds — it must
 * leave the handle readable afterwards, because the generic (emulated) upsert binds the
 * same row more than once (UPDATE, then INSERT when the update matched nothing).
 */
class CdcEventApplierLobTest {

    @TempDir
    Path spillDir;

    private Connection target;
    private CdcEventApplier applier;
    private CdcTableBinding binding;
    private LobSpillSession session;

    @BeforeEach
    void setUp() throws SQLException {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:cdc_applier_lob;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "sa", "");
        try (Statement st = target.createStatement()) {
            st.execute("CREATE TABLE docs (id BIGINT PRIMARY KEY, body CLOB,"
                    + " payload BLOB)");
        }
        binding = docsBinding();
        SyncContext ctx = SyncContext.builder()
                .config(new SyncConfig())
                .targetDialect(new GenericSqlDialect())
                .batchSize(10)
                .build();
        applier = new CdcEventApplier(ctx, new CdcRuntimeStats());
        target.setAutoCommit(false);
        session = LobSpillStore.of(spillDir, 1 << 20).open();
    }

    @AfterEach
    void tearDown() throws SQLException {
        session.close();
        try (Statement st = target.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        target.close();
    }

    @Test
    void aSpilledValueIsBoundAndStaysReadableForTheOwner() throws Exception {
        String body = "中".repeat(2_000);
        byte[] payload = new byte[3_000];
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", 1L);
        row.put("BODY", session.spillText(new java.io.StringReader(body)));
        row.put("PAYLOAD", session.spillBinary(new java.io.ByteArrayInputStream(payload)));

        // Twice in a row: the second apply takes the update branch and re-binds the same
        // spilled handles — only a replayable value can do that.
        applier.applyUpserts(target, binding, List.of(row));
        applier.applyUpserts(target, binding, List.of(row));
        target.commit();

        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT BODY, PAYLOAD FROM docs")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("BODY")).isEqualTo(body);
            assertThat(rs.getBytes("PAYLOAD")).isEqualTo(payload);
        }
        // Ownership stays with the transaction buffer: still readable, still on disk.
        assertThat(CdcLobSpillerTest.read((LobValue) row.get("BODY"))).isEqualTo(body);
        assertThat(spillFiles()).isEqualTo(2);
    }

    @Test
    void aDeleteOfASpilledRowNeedsOnlyTheInlinePrimaryKey() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", 7L);
        row.put("BODY", session.spillText(new java.io.StringReader("x".repeat(500))));
        applier.applyUpserts(target, binding, List.of(row));
        target.commit();

        applier.applyDeletes(target, binding, List.of(row));
        target.commit();

        try (Statement st = target.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM docs")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    private long spillFiles() {
        try (Stream<Path> files = Files.list(spillDir)) {
            return files.count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CdcTableBinding docsBinding() {
        TableMeta meta = new TableMeta();
        meta.setName("docs");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        ColumnMeta body = new ColumnMeta();
        body.setName("BODY");
        body.setJdbcType(Types.CLOB);
        ColumnMeta payload = new ColumnMeta();
        payload.setName("PAYLOAD");
        payload.setJdbcType(Types.BLOB);
        meta.getColumns().add(id);
        meta.getColumns().add(body);
        meta.getColumns().add(payload);
        meta.getPrimaryKeys().add("ID");
        return CdcTableBinding.of("app", "docs", meta, "docs", null);
    }
}
