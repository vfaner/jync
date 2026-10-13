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
        // ORA-65040: mining session started in the wrong container (CDB vs PDB). Same
        // connection will never succeed — park it, don't hot-loop.
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException("ORA-65040: operation not allowed in this container")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
        // describe() preserves the ORA code verbatim for UI display.
        assertThat(CdcFailureClassifier.describe(
                new java.sql.SQLException("ORA-01291: missing logfile"))).contains("ORA-01291");
    }

    // —— P2-5 / P2-13:Oracle 错误码分类补齐与命名理顺 ——

    @Test
    void ora01403NoDataFoundClassifiesAsPurged() {
        // DBMS_LOGMNR.START_LOG_MNR 在「请求的 SCN 区间没有任何 redo 覆盖」时抛
        // ORA-01403(而非 01291)——日志已被回收,重试对同一空区间永远失败,处置与
        // PURGED 相同:park 等人重建基线。当前实现落到 TRANSIENT 热循环,这是 bug。
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException("ORA-01403: no data found")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
    }

    @Test
    void ora01466ClassifiesAsSchemaConflict() {
        // ORA-01466 "table definition has changed" = 挖掘期撞上在途 DDL。它从来不是
        // 认证问题——归到 AUTH_FAILURE 会把 DBA 引向查凭据的错误方向。独立 Kind。
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException(
                        "ORA-01466: unable to read data - table definition has changed")))
                .isEqualTo(CdcFailureClassifier.Kind.SCHEMA_CONFLICT);
    }

    @Test
    void ora65040ClassifiesAsAuthFailure() {
        // P0-2:ORA-65040 "operation not allowed from within a pluggable database" 在
        // LogMiner 语境 = 在 PDB 连接上 START_LOGMNR —— 账号/配置问题(需要 CDB$ROOT
        // 公共挖掘账号),重试永不自愈。归 AUTH_FAILURE park 等人,文案指向挖掘账号。
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException("ORA-65040: operation not allowed from within"
                        + " a pluggable database")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
    }

    @Test
    void ora01555AndOra00060StayTransient() {
        // 守护:01555(快照过旧——新挖掘会话拿新快照可恢复)与 00060(死锁)的重试
        // 语义是刻意设计,归 TRANSIENT 退避重试;谁也不许"顺手"park 它们。
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException("ORA-01555: snapshot too old: rollback segment"
                        + " number 12 with name \"_SYSSMU12$\" too small")))
                .isEqualTo(CdcFailureClassifier.Kind.TRANSIENT);
        assertThat(CdcFailureClassifier.classify(
                new java.sql.SQLException("ORA-00060: deadlock detected while waiting"
                        + " for resource")))
                .isEqualTo(CdcFailureClassifier.Kind.TRANSIENT);
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

    // —— P1-2:PG / SQL Server 失败特征。分类错误的代价是双向的:认证/权限类被归
    // TRANSIENT → 对着永远不会成功的凭据热循环重连;资源耗尽类(连接数满)被归
    // AUTH_FAILURE → 一次抖动把项目 park 进 ERROR 等人救。——

    @Test
    void pgSqlStateAuthClassified() {
        // SQLSTATE 28* = invalid_authorization(28P01 密码错;28000 也是 SQL Server
        // 18456 的状态类);42501 = insufficient_privilege。重试永远无意义。
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "FATAL: password authentication failed for user \"jync\"", "28P01")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "ERROR: permission denied for table orders", "42501")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
    }

    @Test
    void pgAuthMessageClassifiedWithoutSqlState() {
        // 驱动/包装层可能丢 SQLState — 消息特征兜底。
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "FATAL: password authentication failed for user \"jync\"")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "FATAL: no pg_hba.conf entry for host \"10.0.0.2\", user \"jync\"")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
    }

    @Test
    void pgConnectionExhaustionStaysTransient() {
        // 53300 too_many_connections / 57P03 cannot_connect_now:退避重试有意义,
        // 绝不许 park。
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "FATAL: sorry, too many clients already", "53300")))
                .isEqualTo(CdcFailureClassifier.Kind.TRANSIENT);
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "FATAL: the database system is starting up", "57P03")))
                .isEqualTo(CdcFailureClassifier.Kind.TRANSIENT);
    }

    @Test
    void pgSlotExhaustionParks() {
        // max_replication_slots 满:重试永远无意义,等人清 slot — park。
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "ERROR: maximum number of logical replication slots has been reached")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
    }

    @Test
    void sqlServerLoginAndPermissionDeniedPark() {
        // 18456 login failed(mssql-jdbc 给 SQLState 28000)/ 229,230 权限拒绝。
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "Login failed for user 'jync_cdc'.", "28000", 18456)))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "The SELECT permission was denied on the object"
                        + " 'fn_cdc_get_all_changes_dbo_orders_CT'")))
                .isEqualTo(CdcFailureClassifier.Kind.AUTH_FAILURE);
    }
}
