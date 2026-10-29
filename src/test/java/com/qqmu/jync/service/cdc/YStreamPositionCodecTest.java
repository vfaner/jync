package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.cdc.YStreamPositionCodec.YStreamPosition;

/**
 * The packed YStream position is what Jync stores in {@code cdc_position.lsn} and what the
 * resume path feeds back into the client, so two properties have to hold by construction:
 * a round trip is exact, and string order equals commit order (the store's monotonicity
 * check compares it lexicographically).
 */
class YStreamPositionCodecTest {

    @Test
    void encodeIsFixedWidthUppercaseHex() {
        String packed = YStreamPositionCodec.encode(new YStreamPosition(123L, 0, 456L, 7, 8));

        assertThat(packed).hasSize(YStreamPositionCodec.PACKED_LENGTH).hasSize(50);
        assertThat(packed).matches("[0-9A-F]{50}");
        assertThat(packed).isEqualTo("000000000000007B"
                + "00" + "00000000000001C8" + "00000007" + "00000008");
    }

    @Test
    void decodeOfAnEncodedPositionRestoresEveryField() {
        YStreamPosition original = new YStreamPosition(
                1844674407370955161L, 255, 9007199254740993L, 4096, 65535);

        assertThat(YStreamPositionCodec.decode(YStreamPositionCodec.encode(original)))
                .isEqualTo(original);
    }

    @Test
    void aZeroInstanceIdStillOccupiesTwoDigits() {
        // instanceId 是 byte:0 是常态。少打两位会让整串错位,续放时位置就变了。
        String packed = YStreamPositionCodec.encode(new YStreamPosition(0L, 0, 0L, 0, 0));

        assertThat(packed).isEqualTo("0".repeat(50));
    }

    @Test
    void stringOrderEqualsCommitOrder() {
        // 逐段递增:后一段变化必须盖过前一段,字典序才等价于数值序。
        List<YStreamPosition> ascending = List.of(
                new YStreamPosition(10L, 0, 100L, 0, 0),
                new YStreamPosition(10L, 0, 100L, 0, 1),
                new YStreamPosition(10L, 0, 100L, 1, 0),
                new YStreamPosition(10L, 0, 101L, 0, 0),
                new YStreamPosition(10L, 1, 0L, 0, 0),
                new YStreamPosition(11L, 0, 0L, 0, 0));

        for (int i = 0; i < ascending.size(); i++) {
            for (int j = i + 1; j < ascending.size(); j++) {
                String earlier = YStreamPositionCodec.encode(ascending.get(i));
                String later = YStreamPositionCodec.encode(ascending.get(j));
                assertThat(earlier.compareTo(later))
                        .as("%s should sort before %s", earlier, later).isNegative();
            }
        }
    }

    @Test
    void decodeRejectsMalformedText() {
        // 畸形位点必须在续放前失败,而不是悄悄解析成一个别的位点。
        assertThatThrownBy(() -> YStreamPositionCodec.decode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YStreamPositionCodec.decode(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YStreamPositionCodec.decode("0".repeat(49)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YStreamPositionCodec.decode("0".repeat(51)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YStreamPositionCodec.decode("Z".repeat(50)))
                .isInstanceOf(IllegalArgumentException.class);
        // 可读文本形态不是打包形态。
        assertThatThrownBy(() -> YStreamPositionCodec.decode("123-0-456-7-8"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void instanceIdOutsideAByteIsRefusedRatherThanTruncated() {
        assertThatThrownBy(() -> YStreamPositionCodec.encode(
                new YStreamPosition(1L, 256, 1L, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YStreamPositionCodec.encode(
                new YStreamPosition(1L, -1, 1L, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isPackedOnlyClaimsOurExactShape() {
        assertThat(YStreamPositionCodec.isPacked(
                YStreamPositionCodec.encode(new YStreamPosition(1L, 0, 2L, 3, 4)))).isTrue();
        assertThat(YStreamPositionCodec.isPacked(null)).isFalse();
        assertThat(YStreamPositionCodec.isPacked("0/16B3748")).isFalse();
        assertThat(YStreamPositionCodec.isPacked("0x0000002857ae0258000a")).isFalse();
    }

    @Test
    void textFormIsReadableAndUnambiguous() {
        assertThat(new YStreamPosition(123L, 0, 456L, 7, 8).text())
                .isEqualTo("123-0-456-7-8");
    }

    @Test
    void parsesTheServersOwnRestartPositionSpelling() {
        // V$YSTREAM_SERVER.RESTART_POSITION 实测就是这个可读形态
        // (882304965853585408-0-6806-0-0)。基线锚点只能从这里取:START_POSITION 是四元组,
        // 少了 batchRowId,续放会解析失败。
        assertThat(YStreamPositionCodec.parseText("882304965853585408-0-6806-0-0"))
                .isEqualTo(new YStreamPosition(882304965853585408L, 0, 6806L, 0, 0));
        assertThat(YStreamPositionCodec.parseText("123-255-456-7-8"))
                .isEqualTo(new YStreamPosition(123L, 255, 456L, 7, 8));
    }

    @Test
    void textRoundTripIsExact() {
        YStreamPosition original = new YStreamPosition(123L, 7, 456L, 9, 11);

        assertThat(YStreamPositionCodec.parseText(original.text())).isEqualTo(original);
    }

    @Test
    void parseTextRejectsAnythingElse() {
        // 解析出的锚点直接决定少不少数据:宁可拒绝启动,也不能猜。
        for (String bad : new String[]{null, "", "123-0-456-7", "123-0-456-7-8-9",
                "123-0-456-7-x", "-1-0-456-7-8", "123-256-456-7-8", "123 0 456 7 8",
                "0".repeat(50)}) {
            assertThatThrownBy(() -> YStreamPositionCodec.parseText(bad))
                    .as("parsing '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void parseTextRejectsAFieldTooWideForItsType() {
        assertThatThrownBy(() -> YStreamPositionCodec.parseText("123-0-456-7-2147483648"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
