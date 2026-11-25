package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.cdc.CdcTableBinding;

/**
 * The publishing transaction is how the JDBC engines serve a fan-out project without a
 * second stream: one buffered transaction becomes row events plus exactly one commit marker,
 * and the marker carries the position the engine recorded.
 */
class FanoutTargetTransactionTest {

    private static TableMeta orders() {
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        ColumnMeta name = new ColumnMeta();
        name.setName("name");
        name.setJdbcType(Types.VARCHAR);
        meta.getColumns().add(id);
        meta.getColumns().add(name);
        meta.getPrimaryKeys().add("id");
        return meta;
    }

    private static Map<String, Object> row(long id, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        return row;
    }

    @Test
    void commitPublishesRowsThenOneCommitMarkerWithThePosition() throws Exception {
        InMemoryEventTransport transport = new InMemoryEventTransport(16);
        EventTransport.Subscription sub = transport.subscribe("t1");
        FanoutTargetTransaction tx = new FanoutTargetTransaction(transport);
        CdcTableBinding binding = CdcTableBinding.of("src", "orders", orders(), "orders", null);

        tx.applyUpserts(binding, List.of(row(1L, "a"), row(2L, "b")));
        tx.applyDeletes(binding, List.of(row(3L, "c")));
        tx.notePosition(FanoutRuntime.TOKEN_LSN + "0/16B3748");
        tx.commit();

        ChangeEvent first = sub.poll(Duration.ofSeconds(1));
        ChangeEvent second = sub.poll(Duration.ofSeconds(1));
        ChangeEvent third = sub.poll(Duration.ofSeconds(1));
        ChangeEvent marker = sub.poll(Duration.ofSeconds(1));

        assertThat(first.kind()).isEqualTo(ChangeEvent.EventKind.ROW);
        assertThat(first.operation()).isEqualTo(ChangeEvent.ChangeOperation.UPSERT);
        assertThat(first.schema()).isEqualTo("src");
        assertThat(first.table()).isEqualTo("orders");
        assertThat(first.row()).containsEntry("id", 1L);
        assertThat(second.row()).containsEntry("id", 2L);
        assertThat(third.operation()).isEqualTo(ChangeEvent.ChangeOperation.DELETE);
        assertThat(third.oldRow()).containsEntry("id", 3L);
        assertThat(third.row()).isNull();

        assertThat(marker.kind()).isEqualTo(ChangeEvent.EventKind.COMMIT);
        assertThat(marker.position()).isEqualTo("lsn:0/16B3748");
        // Rows and their marker share one transaction id, which is what the worker buffers by.
        assertThat(List.of(first.transactionId(), second.transactionId(),
                third.transactionId(), marker.transactionId()))
                .containsOnly(marker.transactionId());
        assertThat(sub.poll(Duration.ofMillis(50))).isNull();
    }

    @Test
    void aRetriedTransactionReusesTheSameIdSoTheWorkerCanRecover() throws Exception {
        InMemoryEventTransport transport = new InMemoryEventTransport(16);
        EventTransport.Subscription sub = transport.subscribe("t1");
        CdcTableBinding binding = CdcTableBinding.of("src", "orders", orders(), "orders", null);

        FanoutTargetTransaction first = new FanoutTargetTransaction(transport);
        first.applyUpserts(binding, List.of(row(1L, "a")));
        first.notePosition(FanoutRuntime.TOKEN_SCN + "7");
        first.commit();

        // The first publish died before the COMMIT: the engine retries the same source
        // transaction. A fresh random id here made the worker fail with "transaction ended
        // without a COMMIT" forever.
        FanoutTargetTransaction retry = new FanoutTargetTransaction(transport);
        retry.applyUpserts(binding, List.of(row(1L, "a")));
        retry.notePosition(FanoutRuntime.TOKEN_SCN + "7");
        retry.commit();

        String firstRowTx = sub.poll(Duration.ofSeconds(1)).transactionId();
        sub.poll(Duration.ofSeconds(1));
        String retryRowTx = sub.poll(Duration.ofSeconds(1)).transactionId();
        ChangeEvent marker = sub.poll(Duration.ofSeconds(1));
        assertThat(retryRowTx).isEqualTo(firstRowTx);
        assertThat(marker.transactionId()).isEqualTo(firstRowTx);
    }

    @Test
    void anAbortedTransactionPublishesNothing() throws Exception {
        InMemoryEventTransport transport = new InMemoryEventTransport(8);
        EventTransport.Subscription sub = transport.subscribe("t1");
        FanoutTargetTransaction tx = new FanoutTargetTransaction(transport);

        tx.applyUpserts(CdcTableBinding.of("src", "orders", orders(), "orders", null),
                List.of(row(1L, "a")));
        tx.close();

        assertThat(sub.poll(Duration.ofMillis(50))).isNull();
    }
}
