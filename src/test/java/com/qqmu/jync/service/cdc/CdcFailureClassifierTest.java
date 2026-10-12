package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.github.shyiko.mysql.binlog.network.AuthenticationException;

class CdcFailureClassifierTest {

    @Test
    void purgedBinlogClassified() {
        assertThat(CdcFailureClassifier.classify(new IOException(
                "Could not find first log file name in binary log index file (errno: 1236)")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
        assertThat(CdcFailureClassifier.classify(new IOException(
                "The slave is connecting using CHANGE MASTER TO MASTER_AUTO_POSITION = 1, "
                        + "but the master has purged binary logs containing GTIDs that the slave requires")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
    }

    @Test
    void serverIdConflictClassified() {
        assertThat(CdcFailureClassifier.classify(new IOException(
                "A fatal error occurred while trying to connect as a slave: "
                        + "A slave with the same server_uuid/server_id as this slave has connected to the master")))
                .isEqualTo(CdcFailureClassifier.Kind.SERVER_ID_CONFLICT);
    }

    @Test
    void authFailureClassified() {
        assertThat(CdcFailureClassifier.classify(
                new AuthenticationException("Access denied for user 'repl'@'10.0.0.2'")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
    }

    // —— 裁定 R3a:MySQL 8.0 GTID 续传被拒的新措辞必须归入 PURGED(致命,等人重建基线),
    // 否则按 TRANSIENT 无限重连,且撞上 R3b 前的 onConnect 清零就是 1s 热循环。——

    @Test
    void mysql8GtidPurgeWordingClassified() {
        // 8.0.46 服务器原文(IT purge 场景实测捕获)
        assertThat(CdcFailureClassifier.classify(new IOException(
                "Cannot replicate because the source purged required binary logs. Replicate"
                        + " the missing transactions from elsewhere, or provision a new replica"
                        + " from a more recent backup.")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
        // 两个片段各自独立命中(措辞后半句变化时仍有兜底)
        assertThat(CdcFailureClassifier.classify(new IOException(
                "the source purged required binary logs")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
        assertThat(CdcFailureClassifier.classify(new IOException(
                "Replicate the missing transactions from elsewhere")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
    }

    @Test
    void mysql8PurgeWordingFoundInCauseChain() {
        // 链路遍历语义原样保留:新措辞包在两层原因链里也必须被找到
        RuntimeException wrapper = new RuntimeException("connect failed",
                new IOException("stream ended", new IOException(
                        "Cannot replicate because the source purged required binary logs.")));
        assertThat(CdcFailureClassifier.classify(wrapper))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
    }

    @Test
    void nestedCauseFound() {
        RuntimeException wrapper = new RuntimeException("connect failed",
                new IOException("stream ended", new IOException("errno: 1236 purged")));
        assertThat(CdcFailureClassifier.classify(wrapper))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
    }

    @Test
    void oracleCodesClassify() {
        // v1.6.0 LogMiner ORA codes — scanned after the MySQL path so binlog semantics
        // stay byte-identical.
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException("ORA-01291: missing logfile")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException("ORA-00310: archived log contains sequence 42; expected 43")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException("ORA-00308: cannot open archived log '/u01/...'")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException("ORA-01031: insufficient privileges")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
        // describe() preserves the ORA code verbatim for UI display.
        assertThat(CdcFailureClassifier.describe(
                new java.sql.SQLException("ORA-01291: missing logfile"))).contains("ORA-01291");
    }

    @Test
    void everythingElseTransient() {
        assertThat(CdcFailureClassifier.classify(new IOException("Connection reset")))
                .isEqualTo(CdcFailureClassifier.Kind.TRANSIENT);
        assertThat(CdcFailureClassifier.classify(new IllegalStateException("no message at all")))
                .isEqualTo(CdcFailureClassifier.Kind.TRANSIENT);
    }

    @Test
    void describeTruncatesAndReachesRootCause() {
        String longMessage = "x".repeat(900);
        String described = CdcFailureClassifier.describe(
                new RuntimeException("outer", new IOException(longMessage)));
        assertThat(described).contains("x".repeat(10));
        assertThat(described.length()).isLessThanOrEqualTo(520); // 500 + 省略标记
    }

    @Test
    void backoffDoublesAndCaps() {
        CdcReconnectBackoff backoff = new CdcReconnectBackoff(1000L, 60_000L);
        assertThat(backoff.nextDelayMs()).isEqualTo(1000L);
        assertThat(backoff.nextDelayMs()).isEqualTo(2000L);
        assertThat(backoff.nextDelayMs()).isEqualTo(4000L);
        for (int i = 0; i < 20; i++) {
            assertThat(backoff.nextDelayMs()).isLessThanOrEqualTo(60_000L);
        }
        backoff.reset();
        assertThat(backoff.nextDelayMs()).isEqualTo(1000L);
    }

    @Test
    void backoffResetRequiresDemonstratedDwell() {
        // 裁定 R3b:立即断开(存活 0ms)绝不许把退避清零;只有连接真正活过阈值才清
        assertThat(CdcConsumer.shouldResetBackoff(0L)).isFalse();
        assertThat(CdcConsumer.shouldResetBackoff(1L)).isFalse();
        assertThat(CdcConsumer.shouldResetBackoff(
                CdcConsumer.MIN_BACKOFF_RESET_DWELL_MS - 1)).isFalse();
        assertThat(CdcConsumer.shouldResetBackoff(
                CdcConsumer.MIN_BACKOFF_RESET_DWELL_MS)).isTrue();
        assertThat(CdcConsumer.shouldResetBackoff(
                CdcConsumer.MIN_BACKOFF_RESET_DWELL_MS * 10)).isTrue();
    }
}
