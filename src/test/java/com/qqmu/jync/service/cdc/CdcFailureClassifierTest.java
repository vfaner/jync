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

    @Test
    void aTransactionOverTheBufferCapParksInsteadOfRetrying() {
        // 重连必然重放同一个事务、再次超限；退避热循环只会把停机拖成 OOM 反复。
        assertThat(CdcFailureClassifier.classify(new IllegalStateException(
                CdcTransactionBudget.TOO_LARGE_MARKER + ": 1000001 row(s), about 300 byte(s)"
                        + " buffered for one source transaction (limits: 1000000 rows,"
                        + " unlimited bytes).")))
                .isEqualTo(CdcFailureClassifier.Kind.TRANSACTION_TOO_LARGE);
    }

    @Test
    void xaOrderingViolationIsFatalNotTransient() {
        // 源端在 XA 分支准备期间提交了别的被跟踪事务/分支交错:重连只会重放同一段流、
        // 再次命中同一处,退避热重连毫无意义。必须一次性停机等人处置(改用轮询或让
        // 源端串行提交 XA 分支)。
        assertThat(CdcFailureClassifier.classify(new IllegalStateException(
                "Unsupported XA transaction ordering: a transaction committed while branch"
                        + " 'branchA' was prepared. Jync only supports serial two-phase XA")))
                .isEqualTo(CdcFailureClassifier.Kind.XA_ORDERING);
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

    // —— PURGED 详情:把锚点 SCN 与源端最早可用 redo 一并写进错误文本 ——
    // 用户在现场只看到 "PURGED: ORA-01291: 缺少日志文件",没有任何线索说明缺的是哪个
    // SCN 区间、源端还剩多旧的日志。这两个数字就是判断"能不能重试"的全部依据。

    @Test
    void purgedDetailNamesTheResumeAnchorAndTheOldestRedoStillOnTheSource() {
        String detail = CdcFailureClassifier.withPurgeHint(
                "PURGED: ORA-01291: missing logfile", 7_095_423L, 7_106_400L);

        assertThat(detail)
                .startsWith("PURGED: ORA-01291: missing logfile")
                .contains("resume SCN 7095423")
                .contains("oldest redo still on the source starts at SCN 7106400")
                .contains("rebuild the baseline");
    }

    @Test
    void purgedHintOmitsTheSourceFloorWhenTheProbeCouldNotReadIt() {
        String detail = CdcFailureClassifier.withPurgeHint(
                "PURGED: ORA-01291: missing logfile", 7_095_423L, null);

        assertThat(detail)
                .contains("resume SCN 7095423")
                .doesNotContain("oldest redo still on the source")
                .contains("rebuild the baseline");
    }

    @Test
    void purgedHintWithoutAnyScnLeavesTheDetailUntouched() {
        assertThat(CdcFailureClassifier.withPurgeHint("PURGED: ORA-01291", null, null))
                .isEqualTo("PURGED: ORA-01291");
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
    void everyYStreamBlockedConditionParksInsteadOfRetrying() {
        // 这几种都是"重连还是同一堵墙":分块 LOB 行、32 个服务上限、缺表级补充日志、
        // 续放位点已超出日志保留、服务被外部替换。退避热重连只会把停机拖长,
        // 必须一次性停到 ERROR 等人处置。
        for (String detail : java.util.List.of(
                "the transaction contains chunked LOB rows that cannot be assembled",
                "the source already runs 32 YStream services (maximum)",
                "table APP.ORDERS has no table-level supplemental logging",
                "the recover position is outside the retained log",
                "the YStream service was replaced outside Jync")) {
            assertThat(CdcFailureClassifier.classify(YStreamFatalException.blocked(detail)))
                    .as("detail: %s", detail)
                    .isEqualTo(CdcFailureClassifier.Kind.YSTREAM_BLOCKED);
        }
    }

    @Test
    void aYStreamNetworkFailureStillRetries() {
        // 只有带标记的"墙"才 park;同一条流上的网络中断照旧退避重连。
        assertThat(CdcFailureClassifier.classify(new java.sql.SQLException(
                "An I/O error occurred while sending to the backend")))
                .isEqualTo(CdcFailureClassifier.Kind.TRANSIENT);
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
