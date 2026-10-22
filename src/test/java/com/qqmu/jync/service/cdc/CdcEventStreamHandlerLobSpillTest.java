package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.lob.LobValue;

/**
 * The binlog buffer owns spilled values for exactly one transaction: files must exist when
 * the sink runs, be gone once the sink succeeds, and — because a failed sink keeps the
 * buffer for the replay that never re-reads the binlog image — stay until the handler is
 * released. Every other buffer-clearing path (DDL, XA ROLLBACK) deletes them immediately.
 */
class CdcEventStreamHandlerLobSpillTest {

    private static final int THRESHOLD = 50;

    @TempDir
    Path spillDir;

    private final List<CdcEventStreamHandler.TableChange> applied = new ArrayList<>();
    private final List<Integer> spillFilesDuringApply = new ArrayList<>();
    private String bodySeenBySink;

    @Test
    void aFlushedTransactionHandsTheSinkAReadableValueAndDeletesTheFileAfterwards()
            throws Exception {
        CdcEventStreamHandler handler = handler((changes, mark) -> {
            spillFilesDuringApply.add((int) spillFiles());
            // Read it while the sink is running: the files are released right after.
            LobValue value = (LobValue) changes.get(0).upserts.get(0).get("BODY");
            try {
                bodySeenBySink = CdcLobSpillerTest.read(value);
            } catch (IOException e) {
                throw new SQLException(e);
            }
            applied.addAll(changes);
        });

        insert(handler, "中".repeat(100));
        xid(handler);

        assertThat(applied).hasSize(1);
        Map<String, Object> row = applied.get(0).upserts.get(0);
        assertThat(row.get("BODY")).isInstanceOf(LobValue.class);
        assertThat(bodySeenBySink).isEqualTo("中".repeat(100));
        assertThat(spillFilesDuringApply).containsExactly(1);
        assertThat(spillFiles()).isZero();
    }

    @Test
    void aFailedSinkKeepsTheFileUntilTheHandlerIsReleased() throws IOException {
        CdcEventStreamHandler handler = handler((changes, mark) -> {
            throw new SQLException("target unavailable");
        });

        insert(handler, "x".repeat(200));
        assertThatThrownBy(() -> xid(handler)).isInstanceOf(RuntimeException.class);

        assertThat(spillFiles()).isEqualTo(1);   // kept with the buffer for the retry story
        handler.releaseSpill();
        assertThat(spillFiles()).isZero();
    }

    @Test
    void ddlDiscardsTheBufferAndItsSpilledFiles() throws IOException {
        CdcEventStreamHandler handler = handler((changes, mark) -> applied.addAll(changes));

        insert(handler, "d".repeat(200));
        query(handler, "ALTER TABLE docs ADD COLUMN extra INT");

        assertThat(applied).isEmpty();
        assertThat(spillFiles()).isZero();
    }

    @Test
    void xaRollbackDiscardsTheBufferAndItsSpilledFiles() throws IOException {
        CdcEventStreamHandler handler = handler((changes, mark) -> applied.addAll(changes));

        insert(handler, "r".repeat(200));
        query(handler, "XA ROLLBACK 'xid'");

        assertThat(applied).isEmpty();
        assertThat(spillFiles()).isZero();
    }

    @Test
    void releaseSpillIsIdempotentAndSafeWithoutAnySpill() {
        CdcEventStreamHandler handler = handler((changes, mark) -> applied.addAll(changes));

        handler.releaseSpill();
        handler.releaseSpill();

        assertThat(spillFiles()).isZero();
    }

    // --- fixtures -------------------------------------------------------------------

    private CdcEventStreamHandler handler(CdcEventStreamHandler.TransactionSink sink) {
        SyncConfig config = new SyncConfig();
        config.getTables().add("docs");
        CdcTableMetaSupplier supplier = mock(CdcTableMetaSupplier.class);
        when(supplier.binding("app", "docs")).thenReturn(docsBinding());
        return new CdcEventStreamHandler(config, "app", supplier, new CdcRowMapper(),
                new CdcRuntimeStats(), () -> "uuid:1-8", sink,
                LobSpillStore.of(spillDir, 1 << 20), THRESHOLD);
    }

    private static void tableMap(CdcEventStreamHandler handler) {
        TableMapEventData data = new TableMapEventData();
        data.setTableId(7L);
        data.setDatabase("app");
        data.setTable("docs");
        handler.onEvent(event(EventType.TABLE_MAP, data, 100L));
    }

    private static void insert(CdcEventStreamHandler handler, String body) {
        tableMap(handler);
        WriteRowsEventData data = new WriteRowsEventData();
        data.setTableId(7L);
        data.setRows(List.<Serializable[]>of(new Serializable[] {
                1L, body.getBytes(StandardCharsets.UTF_8), new byte[] {1, 2, 3}}));
        handler.onEvent(event(EventType.EXT_WRITE_ROWS, data, 200L));
    }

    private static void xid(CdcEventStreamHandler handler) {
        handler.onEvent(event(EventType.XID, new XidEventData(), 300L));
    }

    private static void query(CdcEventStreamHandler handler, String sql) {
        QueryEventData data = new QueryEventData();
        data.setDatabase("app");
        data.setSql(sql);
        handler.onEvent(event(EventType.QUERY, data, 400L));
    }

    private static Event event(EventType type, Object data, long nextPosition) {
        EventHeaderV4 header = new EventHeaderV4();
        header.setEventType(type);
        header.setNextPosition(nextPosition);
        header.setTimestamp(1760100000000L);
        return new Event(header, (EventData) data);
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

    private long spillFiles() {
        try (Stream<Path> files = Files.list(spillDir)) {
            return files.count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
