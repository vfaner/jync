package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
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
    void compareLsnHandlesThePackedYStreamFormByItsFixedWidth() {
        // YStream 位点是定长打包 hex,写序与字典序一致,所以这里只需按文本比。
        String earlier = YStreamPositionCodec.encode(
                new YStreamPositionCodec.YStreamPosition(10L, 0, 100L, 0, 1));
        String later = YStreamPositionCodec.encode(
                new YStreamPositionCodec.YStreamPosition(10L, 0, 100L, 0, 2));
        assertThat(CdcPositionStore.compareLsn(later, earlier)).isPositive();
        assertThat(CdcPositionStore.compareLsn(earlier, later)).isNegative();
        assertThat(CdcPositionStore.compareLsn(earlier, earlier)).isZero();
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

    // —— 列布局签名:位点必须带上「本事务按什么列序解码」,重启才能发现 DDL 漂移 ——

    private static String signature(char fill) {
        return String.valueOf(fill).repeat(CdcPositionStore.LAYOUT_SIGNATURE_CHARS);
    }

    @Test
    void layoutSignaturesRoundTripThroughAnyIdentifierCharacter() {
        Map<String, String> layouts = new LinkedHashMap<>();
        layouts.put("app.orders", signature('a'));
        layouts.put("schema:odd.表.名", signature('b'));

        String encoded = CdcPositionStore.encodeLayoutSignatures(layouts);

        assertThat(CdcPositionStore.decodeLayoutSignatures(encoded)).isEqualTo(layouts);
    }

    @Test
    void corruptLayoutTextDecodesToWhatIsStillReadable() {
        String encoded = CdcPositionStore.encodeLayoutSignatures(
                Map.of("app.orders", signature('a')));
        assertThat(CdcPositionStore.decodeLayoutSignatures(encoded + "junk"))
                .containsEntry("app.orders", signature('a'));
        assertThat(CdcPositionStore.decodeLayoutSignatures("not-a-layout:text")).isEmpty();
        assertThat(CdcPositionStore.decodeLayoutSignatures(null)).isEmpty();
    }

    @Test
    void advanceMergesNewLayoutsAndKeepsTheOtherTables() {
        position.setBinlogFile("mysql-bin.000007");
        position.setBinlogPos(100L);
        position.setSchemaSignatures(CdcPositionStore.encodeLayoutSignatures(
                Map.of("app.orders", signature('a'))));

        store.advance(1L, "mysql-bin.000007", 200L, null,
                Map.of("app.items", signature('b')));

        assertThat(CdcPositionStore.decodeLayoutSignatures(position.getSchemaSignatures()))
                .containsEntry("app.orders", signature('a'))
                .containsEntry("app.items", signature('b'));
    }

    @Test
    void aDdlAdvanceDropsLayoutsItCanNoLongerVouchFor() {
        // DDL 的目标表未知:未在本事务出现的表,其记录布局可能已经作废。保留它会让
        // 重启把一个已经流过的 DDL 误判成漂移。
        position.setBinlogFile("mysql-bin.000007");
        position.setBinlogPos(100L);
        position.setSchemaSignatures(CdcPositionStore.encodeLayoutSignatures(
                Map.of("app.orders", signature('a'))));

        store.advance(1L, "mysql-bin.000007", 200L, null,
                Map.of("app.items", signature('b')), true);

        assertThat(CdcPositionStore.decodeLayoutSignatures(position.getSchemaSignatures()))
                .containsOnlyKeys("app.items");
    }

    @Test
    void aRejectedRewindDoesNotRecordLayouts() {
        position.setBinlogFile("mysql-bin.000007");
        position.setBinlogPos(500L);

        store.advance(1L, "mysql-bin.000007", 400L, null,
                Map.of("app.orders", signature('a')));

        verify(repository, never()).save(any());
        assertThat(position.getSchemaSignatures()).isNull();
    }

    @Test
    void aBaselineStartForgetsTheLayoutsOfTheInterruptedRun() {
        position.setSchemaSignatures(CdcPositionStore.encodeLayoutSignatures(
                Map.of("app.orders", signature('a'))));

        store.recordBaselineStart(1L, "mysql-bin.000008", 4L, null, 612001L);

        assertThat(position.getSchemaSignatures()).isNull();
    }

    @Test
    void aBaselineStartKeepsTheProjectionSignatureUntilTheBaselineCompletes() {
        // The signature describes the keys that are in Redis NOW. A baseline that has only
        // just anchored has not rewritten them yet, so dropping the signature here loses the
        // only record of their type if the load then fails or is stopped — and the next
        // value-format change reaches the keys unannounced. A completed cache baseline
        // replaces the signature with the contract it actually wrote.
        position.setProjectionSignature("JSON|jync:1:");

        store.recordBaselineStart(1L, "mysql-bin.000008", 4L, null, 612001L);

        assertThat(position.getProjectionSignature()).isEqualTo("JSON|jync:1:");
        assertThat(position.isBaselineDone()).isFalse();
    }

    @Test
    void aTargetSwitchClearsTheAnchorButKeepsTheProjectionSignature() {
        // A kind switch (Redis <-> database) replaces the destination: the anchor and the
        // column layouts describe a target this project no longer writes to. The projection
        // signature does not — it describes the keys this project wrote into Redis, and the
        // switch leaves them untouched. Keeping it is what lets start refuse a value-format
        // change made during a Redis -> JDBC -> Redis round trip instead of answering
        // WRONGTYPE from the baseline.
        position.setBinlogFile("mysql-bin.000007");
        position.setBinlogPos(900L);
        position.setGtidSet("uuid:1-5");
        position.setServerId(612001L);
        position.setScn(42L);
        position.setLsn("0/16B3748");
        position.setSchemaSignatures(CdcPositionStore.encodeLayoutSignatures(
                Map.of("app.orders", signature('a'))));
        position.setProjectionSignature("HASH|jync:1:");
        position.setBaselineDone(true);

        store.resetAnchorKeepingProjectionSignature(1L);

        assertThat(position.getBinlogFile()).isNull();
        assertThat(position.getBinlogPos()).isNull();
        assertThat(position.getGtidSet()).isNull();
        assertThat(position.getServerId()).isNull();
        assertThat(position.getScn()).isNull();
        assertThat(position.getLsn()).isNull();
        assertThat(position.getSchemaSignatures()).isNull();
        assertThat(position.isBaselineDone()).isFalse();
        assertThat(position.getProjectionSignature()).isEqualTo("HASH|jync:1:");
        verify(repository).save(position);
    }

    @Test
    void aDatabaseBaselineDoesNotEraseTheRedisContract() {
        // A JDBC baseline never touches Redis, so the contract of the keys still sitting in
        // the cache remains true. Clearing it on completion is how a former Redis project
        // lost the guard after one JDBC run.
        position.setProjectionSignature("HASH|jync:1:");

        store.markBaselineDone(1L);

        assertThat(position.isBaselineDone()).isTrue();
        assertThat(position.getProjectionSignature()).isEqualTo("HASH|jync:1:");
    }

    @Test
    void resettingAMissingPositionRowIsANoOp() {
        when(repository.findByProjectId(2L)).thenReturn(Optional.empty());

        store.resetAnchorKeepingProjectionSignature(2L); // must not throw

        verify(repository, never()).save(any());
    }
}
