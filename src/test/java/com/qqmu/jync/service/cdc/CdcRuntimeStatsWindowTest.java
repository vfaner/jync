package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.cdc.CdcRuntimeStats.TableChange;

/**
 * CDC 变更日志窗口的纯计数语义:按表聚合 upsert/delete;窗口未到刷新年龄前不取,
 * 到龄后原子交接(取出即从表中移除,后续增量进新窗口,不丢计数);停机 drainAll
 * 兜底剩余;表名大小写不敏感。位点标签按 binlog → SCN → LSN 优先级拼出。
 */
class CdcRuntimeStatsWindowTest {

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final CdcRuntimeStats stats = new CdcRuntimeStats(clock::get);

    @Test
    void nothingIsDueBeforeTheWindowAge() {
        stats.addTableUpserts("ORDERS", 3);
        stats.addTableDeletes("ORDERS", 1);

        // 窗口在 t=1000 打开;刷新年龄 10s,9.999s 仍不可取
        clock.set(10_999L);
        assertThat(stats.drainDueWindows(10_000L, clock.get())).isEmpty();
        // 累计总数仍在
        assertThat(stats.getRowsApplied()).isEqualTo(4L);

        clock.set(11_000L);
        List<TableChange> due = stats.drainDueWindows(10_000L, clock.get());
        assertThat(due).hasSize(1);
        assertThat(due.get(0).table()).isEqualTo("ORDERS");
        assertThat(due.get(0).upserts()).isEqualTo(3L);
        assertThat(due.get(0).deletes()).isEqualTo(1L);
    }

    @Test
    void countsAfterADrainOpenAFreshWindowAndNothingIsLost() {
        stats.addTableUpserts("orders", 5);
        clock.set(20_000L);
        assertThat(stats.drainDueWindows(10_000L, clock.get())).hasSize(1);

        // 取出之后的增量进新窗口:旧窗口已不在,立即 drain 取不到(新窗口年龄为 0)
        stats.addTableUpserts("ORDERS", 2);   // 大小写不同,同一逻辑表
        stats.addTableDeletes("Orders", 4);
        assertThat(stats.drainDueWindows(10_000L, clock.get())).isEmpty();

        clock.set(40_000L);
        List<TableChange> due = stats.drainDueWindows(10_000L, clock.get());
        assertThat(due).hasSize(1);
        assertThat(due.get(0).table()).isEqualTo("ORDERS"); // 新窗口首个键的拼写保留
        assertThat(due.get(0).upserts()).isEqualTo(2L);
        assertThat(due.get(0).deletes()).isEqualTo(4L);
    }

    @Test
    void multipleTablesDrainInCaseInsensitiveOrder() {
        stats.addTableUpserts("ZEBRA", 1);
        clock.set(2_000L);
        stats.addTableDeletes("apple", 1);
        clock.set(20_000L); // zebra 到龄;apple 只活了 18s(窗口在 2000 打开,20000 已到龄)
        List<TableChange> due = stats.drainDueWindows(10_000L, clock.get());
        assertThat(due).extracting(TableChange::table).containsExactly("apple", "ZEBRA");
    }

    @Test
    void drainAllReturnsUndueWindowsAndEmptiesTheMap() {
        stats.addTableUpserts("ORDERS", 7);
        stats.addTableDeletes("AUDIT", 2);

        List<TableChange> all = stats.drainAllWindows();
        assertThat(all).extracting(TableChange::table).containsExactly("AUDIT", "ORDERS");
        assertThat(stats.drainAllWindows()).isEmpty();
    }

    @Test
    void nonPositiveCountsAndNullTablesAreIgnored() {
        stats.addTableUpserts("ORDERS", 0);
        stats.addTableDeletes(null, 3);
        assertThat(stats.drainAllWindows()).isEmpty();
        assertThat(stats.getRowsApplied()).isZero();
    }

    @Test
    void positionLabelPrefersBinlogThenScnThenLsn() {
        assertThat(stats.positionLabel()).isNull();
        stats.recordPositionLsn("0/167D3F8");
        assertThat(stats.positionLabel()).isEqualTo("LSN 0/167D3F8");
        stats.recordPositionScn(2_112_284L);
        assertThat(stats.positionLabel()).isEqualTo("SCN 2112284");
        stats.recordPosition("mysql-bin.000003", 154L, null);
        assertThat(stats.positionLabel()).isEqualTo("mysql-bin.000003:154");
    }
}
