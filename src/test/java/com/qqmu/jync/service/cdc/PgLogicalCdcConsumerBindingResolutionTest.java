package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;

/**
 * 无主键表的「不可应用」判定(BindingSupplier 的哨兵)一旦缓存就永不重判:表后来
 * {@code ALTER TABLE … ADD PRIMARY KEY},消费者仍按旧结论跳过它的全部行事件,直到
 * 进程重启或基线重建。逻辑复制槽不发行 DDL,没有任何事件能触发重读 —— 只能给判定
 * 本身加一个保质期。这里钉住期满判定的边界(纯函数,不依赖真时钟)。
 */
class PgLogicalCdcConsumerBindingResolutionTest {

    private static final long MINUTE = 60_000L;

    private static CdcTableBinding binding() {
        ColumnMeta id = new ColumnMeta();
        id.setName("id");
        id.setJdbcType(Types.BIGINT);
        id.setPrimaryKey(true);
        TableMeta meta = new TableMeta();
        meta.setName("orders");
        meta.getColumns().add(id);
        meta.getPrimaryKeys().add("id");
        return CdcTableBinding.of("public", "orders", meta, "orders", Collections.emptyMap());
    }

    @Test
    void anUnmappableVerdictExpiresSoALaterPrimaryKeyIsPickedUp() {
        long createdAt = 1_000_000L;
        PgLogicalCdcConsumer.Resolution sentinel =
                PgLogicalCdcConsumer.Resolution.unmappable(createdAt);

        assertThat(sentinel.recheckDue(createdAt + 9 * MINUTE)).isFalse();
        assertThat(sentinel.recheckDue(createdAt + 11 * MINUTE)).isTrue();
    }

    @Test
    void aMappedBindingNeverNeedsARecheck() {
        PgLogicalCdcConsumer.Resolution mapped =
                PgLogicalCdcConsumer.Resolution.of(binding());

        assertThat(mapped.recheckDue(Long.MAX_VALUE)).isFalse();
    }
}
