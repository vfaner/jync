package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.config.SyncProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A replication slot that disappears while a committed position exists is a silent data
 * loss, not a hiccup.
 *
 * <p>{@code ensureSlot} used to create a missing slot unconditionally. After the slot was
 * dropped (operator cleanup, {@code enable_slot_log=off} on openGauss surviving a restart,
 * a restored standby, a truncated WAL) the freshly created slot starts at "now": every
 * change committed since the stored LSN is gone, yet the consumer resumes, advances the
 * stored position from the new stream and reports a healthy project. The guard turns that
 * into a PURGED-classified failure so the project parks and asks for a re-baseline.
 */
class PgLogicalCdcSlotGuardTest {

    private Connection conn;
    private Statement check;
    private Statement create;

    private void sourceWithSlot(boolean present) throws SQLException {
        conn = mock(Connection.class);
        check = mock(Statement.class);
        create = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(present);
        when(check.executeQuery(anyString())).thenReturn(rs);
        // The existence check is always the first statement; a create is a second one.
        when(conn.createStatement()).thenReturn(check, create);
    }

    @Test
    void anExistingSlotIsLeftAloneEvenWhenAPositionIsCommitted() throws Exception {
        sourceWithSlot(true);

        PgLogicalCdcConsumer.ensureSlot(conn, "jync_1", "0/16B6C50");

        verify(create, never()).execute(anyString());
    }

    @Test
    void aMissingSlotIsCreatedBeforeTheFirstCommit() throws Exception {
        sourceWithSlot(false);

        PgLogicalCdcConsumer.ensureSlot(conn, "jync_1", null);

        verify(create).execute(contains("pg_create_logical_replication_slot"));
    }

    /** A blank LSN is "no committed position yet" — baseline not finished, create freely. */
    @Test
    void aMissingSlotWithABlankPositionIsStillCreated() throws Exception {
        sourceWithSlot(false);

        PgLogicalCdcConsumer.ensureSlot(conn, "jync_1", "  ");

        verify(create).execute(contains("pg_create_logical_replication_slot"));
    }

    @Test
    void aMissingSlotAfterACommitIsReportedAsAPurge() throws Exception {
        sourceWithSlot(false);

        assertThatThrownBy(() -> PgLogicalCdcConsumer.ensureSlot(conn, "jync_1", "0/16B6C50"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("PG cdc purge")
                .hasMessageContaining("jync_1")
                .hasMessageContaining("0/16B6C50");
        // Creating the slot here is exactly the bug: it would start at "now" and the
        // gap between the stored LSN and now would never be read.
        verify(create, never()).execute(anyString());
    }

    // —— 槽位 WAL 滞留量(openGauss 没有 pg_lsn,必须静默降级)——

    @Test
    void retainedWalReadsTheSlotDistance() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(contains("pg_wal_lsn_diff"))).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(1_234_567L);

        assertThat(PgLogicalCdcConsumer.readRetainedWalBytes(conn, "jync_1"))
                .isEqualTo(1_234_567L);
    }

    @Test
    void retainedWalIsUnknownWhenTheSlotRowIsGone() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(contains("pg_wal_lsn_diff"))).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertThat(PgLogicalCdcConsumer.readRetainedWalBytes(conn, "jync_1")).isNull();
    }

    /** A SQL NULL (no restart_lsn yet) must not read as "0 bytes held". */
    @Test
    void retainedWalIsUnknownWhenTheColumnIsNull() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(contains("pg_wal_lsn_diff"))).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(0L);
        when(rs.wasNull()).thenReturn(true);

        assertThat(PgLogicalCdcConsumer.readRetainedWalBytes(conn, "jync_1")).isNull();
    }

    /**
     * openGauss has no {@code pg_lsn} type, so the whole statement fails (42883
     * undefined_function). That must degrade to "unknown" — never fail the cycle: the
     * consumer's job is replication, and the gauge is diagnostic.
     */
    @Test
    void retainedWalDegradesQuietlyWhenTheServerLacksTheFunction() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(contains("pg_wal_lsn_diff")))
                .thenThrow(new SQLException("function pg_wal_lsn_diff does not exist", "42883"));

        assertThat(PgLogicalCdcConsumer.readRetainedWalBytes(conn, "jync_1")).isNull();
    }

    /** The sentinel must classify as PURGED so the consumer parks instead of retrying. */
    @Test
    void thePurgeSentinelClassifiesAsPurged() {
        assertThat(CdcFailureClassifier.classify(new SQLException(
                "PG cdc purge: replication slot 'jync_1' is gone")))
                .isEqualTo(CdcFailureClassifier.Kind.PURGED);
    }

    // —— 释放槽(删除任务 / 切换策略 / 更换源库三条路径共用)——

    /**
     * 三条释放路径都会在"槽根本不存在"的源上调用:项目从未启动过 CDC、DBA 已经手工
     * 清理过、或者上一次释放已经做过。PG 抛 42704 undefined_object,那不是需要人工
     * 处理的状况;打 WARN 只会训练用户忽略这条真正重要的告警(槽还在、WAL 在涨)。
     */
    @Test
    void releasingASlotThatIsAlreadyGoneIsNotAWarning() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.execute(contains("pg_drop_replication_slot")))
                .thenThrow(new SQLException("replication slot \"jync_1\" does not exist", "42704"));

        ListAppender<ILoggingEvent> logs = LogCapture.attach(PgLogicalCdcConsumer.class);
        try {
            PgLogicalCdcConsumer.dropSlotQuietly(conn, 1L, new SyncProperties());

            assertThat(LogCapture.messagesAt(Level.WARN, logs)).isEmpty();
        } finally {
            LogCapture.detach(PgLogicalCdcConsumer.class, logs);
        }
    }

    /**
     * 55006 object_in_use 表示消费者还没退干净。此时叫用户"手工 drop"是做不到的
     * —— 活跃槽 PG 拒绝删除。告警必须指向真正的原因和下一步。
     */
    @Test
    void releasingASlotStillInUseSaysWhyItCannotBeDropped() throws Exception {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.execute(contains("pg_drop_replication_slot")))
                .thenThrow(new SQLException("replication slot \"jync_1\" is active for PID 42",
                        "55006"));

        ListAppender<ILoggingEvent> logs = LogCapture.attach(PgLogicalCdcConsumer.class);
        try {
            PgLogicalCdcConsumer.dropSlotQuietly(conn, 1L, new SyncProperties());

            assertThat(LogCapture.messagesAt(Level.WARN, logs))
                    .singleElement()
                    .satisfies(message -> assertThat(message)
                            .contains("still in use")
                            .doesNotContain("drop it by hand"));
        } finally {
            LogCapture.detach(PgLogicalCdcConsumer.class, logs);
        }
    }
}
