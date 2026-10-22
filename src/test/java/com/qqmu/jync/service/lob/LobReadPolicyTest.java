package com.qqmu.jync.service.lob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Decides, per column, whether a value is read inline, streamed once, or spilled.
 *
 * <p>The rules that matter to correctness: a primary-key/cursor/cleansed column is never
 * allowed to become a {@code LobValue} (the cursor writer and the cleanser need a real
 * value), and when such a column is genuinely too large the failure has to name the
 * table, the column and the setting to raise — not silently truncate or OOM.
 */
class LobReadPolicyTest {

    @Test
    void disabledPolicyInlinesEverything() {
        LobReadPolicy policy = LobReadPolicy.disabled();
        assertThat(policy.modeFor("payload", Types.CLOB, 0)).isEqualTo(LobReadPolicy.Mode.INLINE);
        assertThat(policy.modeFor("payload", Types.LONGVARBINARY, 0))
                .isEqualTo(LobReadPolicy.Mode.INLINE);
        assertThat(policy.enabled()).isFalse();
    }

    @Test
    void declaredLargeTypesStreamEvenWhenSizeIsUnknown() {
        LobReadPolicy policy = LobReadPolicy.forTable("t_doc", LobReadPolicy.Mode.ONE_SHOT,
                1024, 1 << 20, List.of());
        assertThat(policy.modeFor("body", Types.CLOB, 0)).isEqualTo(LobReadPolicy.Mode.ONE_SHOT);
        assertThat(policy.modeFor("body", Types.LONGVARCHAR, 0))
                .isEqualTo(LobReadPolicy.Mode.ONE_SHOT);
        assertThat(policy.modeFor("body", Types.BLOB, 0)).isEqualTo(LobReadPolicy.Mode.ONE_SHOT);
        assertThat(policy.modeFor("body", Types.LONGVARBINARY, 0))
                .isEqualTo(LobReadPolicy.Mode.ONE_SHOT);
    }

    @Test
    void varcharStreamsOnlyAboveTheInlineThreshold() {
        LobReadPolicy policy = LobReadPolicy.forTable("t_doc", LobReadPolicy.Mode.ONE_SHOT,
                1024, 1 << 20, List.of());
        assertThat(policy.modeFor("small", Types.VARCHAR, 255))
                .isEqualTo(LobReadPolicy.Mode.INLINE);
        assertThat(policy.modeFor("wide", Types.VARCHAR, 65535))
                .isEqualTo(LobReadPolicy.Mode.ONE_SHOT);
        assertThat(policy.modeFor("unknown", Types.VARCHAR, 0))
                .isEqualTo(LobReadPolicy.Mode.INLINE);
    }

    @Test
    void forceInlineColumnsAlwaysStayInline() {
        LobReadPolicy policy = LobReadPolicy.forTable("t_doc", LobReadPolicy.Mode.ONE_SHOT,
                1024, 8192, List.of("ID", "updated_at"));
        assertThat(policy.modeFor("id", Types.LONGVARCHAR, 0))
                .isEqualTo(LobReadPolicy.Mode.INLINE);
        assertThat(policy.modeFor("UPDATED_AT", Types.CLOB, 0))
                .isEqualTo(LobReadPolicy.Mode.INLINE);
    }

    @Test
    void forceInlineColumnTooLargeFailsWithTableColumnAndSetting() {
        LobReadPolicy policy = LobReadPolicy.forTable("t_doc", LobReadPolicy.Mode.ONE_SHOT,
                1024, 8192, List.of("id"));
        assertThatThrownBy(() -> policy.modeFor("id", Types.VARCHAR, 100_000))
                .isInstanceOf(LobLimitExceededException.class)
                .hasMessageContaining("t_doc")
                .hasMessageContaining("id")
                .hasMessageContaining("sync.lob.force-inline-max-bytes");
    }

    @Test
    void declaredSizesFromMetadataDriveTheSizeCheck() {
        LobReadPolicy policy = LobReadPolicy.forTable("t_doc", LobReadPolicy.Mode.ONE_SHOT,
                1024, 1 << 20, List.of())
                .withDeclaredSizes(java.util.Map.of("wide", 65535L, "small", 10L));
        assertThat(policy.modeFor("WIDE", Types.VARCHAR)).isEqualTo(LobReadPolicy.Mode.ONE_SHOT);
        assertThat(policy.modeFor("small", Types.VARCHAR)).isEqualTo(LobReadPolicy.Mode.INLINE);
        assertThat(policy.declaredSize("wide")).isEqualTo(65535L);
        assertThat(policy.declaredSize("absent")).isZero();
    }

    @Test
    void spillModeIsUsedForReplayableTargets() {
        LobReadPolicy policy = LobReadPolicy.forTable("t_doc", LobReadPolicy.Mode.SPILL,
                1024, 1 << 20, List.of());
        assertThat(policy.modeFor("body", Types.CLOB, 0)).isEqualTo(LobReadPolicy.Mode.SPILL);
    }
}
