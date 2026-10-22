package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.lob.LobValue;
import com.qqmu.jync.service.lob.LobValues;

/**
 * A CDC transaction buffer holds whole row maps until the XID boundary. This spiller
 * replaces oversized text/binary cells with a replayable on-disk handle so the buffer's
 * heap stays bounded, while leaving every column another code path needs as a String in
 * memory: primary keys (fold/dedupe/delete), and columns a column rule must transform.
 */
class CdcLobSpillerTest {

    @TempDir
    Path spillDir;

    private LobSpillStore store;
    private CdcLobSpiller spiller;

    @BeforeEach
    void setUp() {
        store = LobSpillStore.of(spillDir, 1 << 20);
        spiller = new CdcLobSpiller(store, 50, new SyncConfig());
    }

    @AfterEach
    void tearDown() {
        spiller.close();
    }

    @Test
    void spillsTextAboveTheThreshold() throws IOException {
        Map<String, Object> row = row("中".repeat(100));

        spiller.spill(docs(), row);

        assertThat(row.get("BODY")).isInstanceOf(LobValue.class);
        LobValue value = (LobValue) row.get("BODY");
        assertThat(value.kind()).isEqualTo(LobValue.Kind.TEXT);
        assertThat(value.length()).isEqualTo(100);
        assertThat(value.replayable()).isTrue();
        assertThat(read(value)).isEqualTo("中".repeat(100));
    }

    @Test
    void keepsValuesAtOrBelowTheThresholdInMemory() {
        Map<String, Object> row = row("中".repeat(16));   // 48 UTF-8 bytes

        spiller.spill(docs(), row);

        assertThat(row.get("BODY")).isEqualTo("中".repeat(16));
    }

    @Test
    void spillsBinaryValuesAboveTheThreshold() throws IOException {
        byte[] payload = new byte[51];
        Map<String, Object> row = row("short");
        row.put("PAYLOAD", payload);

        spiller.spill(docs(), row);

        assertThat(row.get("PAYLOAD")).isInstanceOf(LobValue.class);
        LobValue value = (LobValue) row.get("PAYLOAD");
        assertThat(value.kind()).isEqualTo(LobValue.Kind.BINARY);
        assertThat(value.length()).isEqualTo(51);
    }

    /** The fold compares PK values and the delete path re-binds them; they must stay in memory. */
    @Test
    void neverSpillsPrimaryKeyColumns() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", "k".repeat(500));
        row.put("BODY", "short");

        spiller.spill(docs(), row);

        assertThat(row.get("ID")).isEqualTo("k".repeat(500));
    }

    /** A TRIM/UPPER rule is String-only; such a column must be read whole or the rule silently skips it. */
    @Test
    void neverSpillsColumnsAHandWrittenRuleTouches() {
        SyncConfig config = new SyncConfig();
        ColumnRule trim = new ColumnRule();
        trim.setTable("docs");
        trim.setColumn("BODY");
        trim.setType(ColumnRuleType.TRIM);
        config.setColumnRules(List.of(trim));
        CdcLobSpiller ruled = new CdcLobSpiller(store, 50, config);
        try {
            Map<String, Object> row = row("  " + "x".repeat(100) + "  ");

            ruled.spill(docs(), row);

            assertThat(row.get("BODY")).isInstanceOf(String.class);
        } finally {
            ruled.close();
        }
    }

    @Test
    void closeDeletesEverySpilledFile() {
        spiller.spill(docs(), row("中".repeat(100)));

        spiller.close();

        assertThat(spillFiles()).isZero();
    }

    @Test
    void disabledSpillerLeavesValuesUntouched() {
        CdcLobSpiller disabled = CdcLobSpiller.disabled();
        Map<String, Object> row = row("中".repeat(100));

        disabled.spill(docs(), row);

        assertThat(row.get("BODY")).isInstanceOf(String.class);
    }

    @Test
    void doesNotWrapAValueThatIsAlreadyALobHandle() {
        LobValue inMemory = LobValues.text("already a handle");
        Map<String, Object> row = row("short");
        row.put("BODY", inMemory);

        spiller.spill(docs(), row);

        assertThat(row.get("BODY")).isSameAs(inMemory);
        ((LobValue) row.get("BODY")).release();
    }

    @Test
    void everySpilledValueSurvivesUntilTheSessionIsClosed() throws IOException {
        Map<String, Object> first = row("a".repeat(100));
        Map<String, Object> second = row("b".repeat(100));

        spiller.spill(docs(), first);
        spiller.spill(docs(), second);

        assertThat(read((LobValue) first.get("BODY"))).isEqualTo("a".repeat(100));
        assertThat(read((LobValue) second.get("BODY"))).isEqualTo("b".repeat(100));
        assertThat(spillFiles()).isEqualTo(2);
    }

    static String read(LobValue value) throws IOException {
        try (Reader reader = value.openReader()) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                out.append(buffer, 0, n);
            }
            return out.toString();
        }
    }

    private long spillFiles() {
        try (Stream<Path> files = Files.list(spillDir)) {
            return files.count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> row(String body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", 1L);
        row.put("BODY", body);
        row.put("PAYLOAD", new byte[] {1, 2, 3});
        return row;
    }

    private static CdcTableBinding docs() {
        TableMeta meta = new TableMeta();
        meta.setName("docs");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        ColumnMeta body = new ColumnMeta();
        body.setName("BODY");
        body.setJdbcType(Types.LONGVARCHAR);
        ColumnMeta payload = new ColumnMeta();
        payload.setName("PAYLOAD");
        payload.setJdbcType(Types.LONGVARBINARY);
        meta.getColumns().add(id);
        meta.getColumns().add(body);
        meta.getColumns().add(payload);
        meta.getPrimaryKeys().add("ID");
        return CdcTableBinding.of("app", "docs", meta, "docs_dst", null);
    }
}
