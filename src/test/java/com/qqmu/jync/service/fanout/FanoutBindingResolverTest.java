package com.qqmu.jync.service.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.service.cdc.CdcTableBinding;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * Every fan-out target shares one source connection for catalog reads. JDBC connections are
 * not thread-safe — SQL Server's driver corrupts its TDS stream when two workers read metadata
 * at once — so the resolver must serialize those reads. A failed read must also stay
 * uncached: one driver hiccup must not mark a table unsyncable for the whole run.
 */
class FanoutBindingResolverTest {

    private static TableMeta keyed(String name) {
        TableMeta meta = new TableMeta();
        meta.setName(name);
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        meta.getColumns().add(id);
        meta.getPrimaryKeys().add("id");
        return meta;
    }

    private static SyncContext ctx(MetadataReader reader) {
        return SyncContext.builder()
                .config(new SyncConfig())
                .sourceSchema("dbo")
                .sourceReader(reader)
                .build();
    }

    private static MetadataReader answering(
            org.mockito.stubbing.Answer<TableMeta> answer) throws SQLException {
        MetadataReader reader = mock(MetadataReader.class);
        when(reader.readTable(any(), any(), any())).thenAnswer(answer);
        return reader;
    }

    @Test
    void concurrentTargetsNeverReadTheSharedConnectionAtTheSameTime() throws Exception {
        AtomicBoolean inUse = new AtomicBoolean();
        AtomicInteger overlaps = new AtomicInteger();
        MetadataReader reader = answering(invocation -> {
            if (!inUse.compareAndSet(false, true)) {
                overlaps.incrementAndGet();
                throw new SQLException("concurrent metadata read");
            }
            try {
                Thread.sleep(15L);
                return keyed(invocation.getArgument(2));
            } finally {
                inUse.set(false);
            }
        });
        FanoutBindingResolver resolver = new FanoutBindingResolver(ctx(reader), null);

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        List<CdcTableBinding> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            String table = "t" + i;
            Thread worker = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    seen.add(resolver.resolve("dbo", table));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(5_000L);
        }

        assertThat(overlaps.get()).isZero();
        assertThat(seen).hasSize(threads).doesNotContainNull();
    }

    @Test
    void aFailedReadIsRetriedInsteadOfCachedAsUnsyncable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MetadataReader reader = answering(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new SQLException("driver hiccup");
            }
            return keyed(invocation.getArgument(2));
        });
        FanoutBindingResolver resolver = new FanoutBindingResolver(ctx(reader), null);

        assertThat(resolver.resolve("dbo", "orders")).isNull();
        assertThat(resolver.resolve("dbo", "orders")).isNotNull();
    }
}
