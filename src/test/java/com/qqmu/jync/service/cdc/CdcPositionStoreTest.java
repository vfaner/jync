package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.repository.CdcPositionRepository;

/**
 * P1-3:位点单调性保护。位点回退的代价是「重放窗口错乱」:崩溃重启后从更老的位点
 * 重拉固然被幂等吸收,但若回退发生在 slot/位点已落定之后(PG rollback 边界、双写
 * 者竞态),记录的位点就不再描述目标端真实状态。三套 advance 都必须:前进→保存,
 * 回退/等值→WARN+拒绝保存。未知格式宁可放行(误拒会卡死位点推进=真丢数据,
 * 放行最多是一次幂等重放)。
 */
class CdcPositionStoreTest {

    private CdcPositionRepository repository;
    private CdcPositionStore store;
    private CdcPosition position;

    @BeforeEach
    void setUp() {
        repository = mock(CdcPositionRepository.class);
        store = new CdcPositionStore(repository);
        position = new CdcPosition();
        position.setProjectId(1L);
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(position));
    }

    // —— compareLsn:PG "0/16B3748"(高/低两段 hex)与 SQLServer "0x..."(定长 hex)——

    @Test
    void compareLsnHandlesPgHighLowFormat() {
        assertThat(CdcPositionStore.compareLsn("0/16B3748", "0/16B3740")).isPositive();
        assertThat(CdcPositionStore.compareLsn("0/16B3740", "0/16B3748")).isNegative();
        assertThat(CdcPositionStore.compareLsn("0/16B3748", "0/16B3748")).isZero();
        // 高位段压过低位段:1/0 > 0/FFFFFFFF。
        assertThat(CdcPositionStore.compareLsn("1/0", "0/FFFFFFFF")).isPositive();
        // 大小写不敏感(hex 数值语义)。
        assertThat(CdcPositionStore.compareLsn("0/16b3748", "0/16B3748")).isZero();
    }

    @Test
    void compareLsnHandlesSqlServerHexFormat() {
        assertThat(CdcPositionStore.compareLsn(
                "0x0000002857ae0258000b", "0x0000002857ae0258000a")).isPositive();
        assertThat(CdcPositionStore.compareLsn(
                "0x0000002857ae0258000A", "0x0000002857ae0258000b")).isNegative();
        assertThat(CdcPositionStore.compareLsn(
                "0x0000002857ae0258000a", "0x0000002857ae0258000a")).isZero();
        // 长度不同(不同来源手写值)也按数值比较,不按字符串长度。
        assertThat(CdcPositionStore.compareLsn("0xFF", "0x100")).isNegative();
    }

    @Test
    void compareLsnOnUnknownFormatIsConservative() {
        // null 参与、无法解析的格式:不给出「回退」结论(误拒会卡死位点推进)。
        assertThat(CdcPositionStore.compareLsn(null, "0/1")).isZero();
        assertThat(CdcPositionStore.compareLsn("0/1", null)).isZero();
        assertThat(CdcPositionStore.compareLsn("garbage", "nonsense")).isZero();
    }

    // —— advanceLsn:回退拒绝,前进保存 ——

    @Test
    void lsnRewindIsRejectedAndForwardIsSaved() {
        position.setLsn("0/200");

        store.advanceLsn(1L, "0/100");
        verify(repository, never()).save(any());

        store.advanceLsn(1L, "0/200"); // 等值:已是该值,无需保存
        verify(repository, never()).save(any());

        store.advanceLsn(1L, "0/300");
        verify(repository).save(position);
        assertThat(position.getLsn()).isEqualTo("0/300");
    }

    @Test
    void lsnAdvanceFromNullAlwaysSaves() {
        position.setLsn(null);
        store.advanceLsn(1L, "0/100");
        verify(repository).save(position);
        assertThat(position.getLsn()).isEqualTo("0/100");
    }

    // —— advanceScn:数值比较,回退拒绝 ——

    @Test
    void scnRewindIsRejectedAndForwardIsSaved() {
        position.setScn(100L);

        store.advanceScn(1L, 90L);
        verify(repository, never()).save(any());

        store.advanceScn(1L, 100L);
        verify(repository, never()).save(any());

        store.advanceScn(1L, 101L);
        verify(repository).save(position);
        assertThat(position.getScn()).isEqualTo(101L);
    }

    // —— advance(binlog):同文件比 pos,跨文件比文件名数值段 ——

    @Test
    void binlogRewindWithinSameFileIsRejected() {
        position.setBinlogFile("mysql-bin.000007");
        position.setBinlogPos(4711L);

        store.advance(1L, "mysql-bin.000007", 4000L, null);
        verify(repository, never()).save(any());

        store.advance(1L, "mysql-bin.000007", 5000L, null);
        verify(repository).save(position);
        assertThat(position.getBinlogPos()).isEqualTo(5000L);
    }

    @Test
    void binlogFileNumericOrderDecidesAcrossFiles() {
        position.setBinlogFile("mysql-bin.000007");
        position.setBinlogPos(4711L);

        // 老文件的高 pos 也是回退。
        store.advance(1L, "mysql-bin.000006", 99999L, null);
        verify(repository, never()).save(any());

        // 新文件的低 pos 是前进。
        store.advance(1L, "mysql-bin.000008", 4L, null);
        verify(repository).save(position);
        assertThat(position.getBinlogFile()).isEqualTo("mysql-bin.000008");
    }

    @Test
    void unparseableBinlogFilePassesThrough() {
        position.setBinlogFile("weird-name");
        position.setBinlogPos(10L);

        // 提不出数值段:放行保存(误拒会卡死位点推进)。
        store.advance(1L, "other-weird", 5L, null);
        verify(repository).save(position);
        assertThat(position.getBinlogFile()).isEqualTo("other-weird");
    }

    @Test
    void gtidSetStillUpdatesOnForwardAdvance() {
        position.setBinlogFile("mysql-bin.000007");
        position.setBinlogPos(4711L);

        store.advance(1L, "mysql-bin.000007", 5000L, "uuid:1-90");
        verify(repository).save(position);
        assertThat(position.getGtidSet()).isEqualTo("uuid:1-90");
    }
}
