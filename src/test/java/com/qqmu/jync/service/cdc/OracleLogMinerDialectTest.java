package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * The floor of the source's mineable redo — used by the baseline's post-load check and by
 * the PURGED detail. It must read the smallest FIRST_CHANGE# across the archived logs and
 * the CURRENT online group, and it must distinguish "0" from "no rows / SQL NULL".
 */
class OracleLogMinerDialectTest {

    private Connection connWithFloor(Boolean hasRow, Long floor) throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        ResultSet rs = mock(ResultSet.class);
        when(st.executeQuery(contains("MIN(FIRST_CHANGE#)"))).thenReturn(rs);
        when(rs.next()).thenReturn(hasRow);
        when(rs.getLong(1)).thenReturn(floor == null ? 0L : floor);
        when(rs.wasNull()).thenReturn(floor == null);
        return conn;
    }

    @Test
    void readsTheSmallestFirstChangeAcrossArchivedAndCurrentRedo() throws SQLException {
        Connection conn = connWithFloor(true, 7_000_000L);

        assertThat(OracleLogMinerDialect.INSTANCE.oldestMineableScn(conn))
                .isEqualTo(7_000_000L);
        // Both sources must be in play: archived files alone miss a CURRENT group that has
        // not been archived yet, and the online group alone misses every older archive.
        verify(conn.createStatement()).executeQuery(contains("V$ARCHIVED_LOG"));
        verify(conn.createStatement()).executeQuery(contains("V$LOG"));
    }

    @Test
    void noRowsMeansUnknownNotZero() throws SQLException {
        assertThat(OracleLogMinerDialect.INSTANCE.oldestMineableScn(connWithFloor(false, null)))
                .isNull();
    }

    @Test
    void sqlNullMeansUnknownNotZero() throws SQLException {
        // A fresh ARCHIVELOG database with nothing archived yet: MIN() over zero rows is
        // SQL NULL, which getLong reports as 0. Returning 0 would make every anchor look
        // covered and silently disable the gap check.
        assertThat(OracleLogMinerDialect.INSTANCE.oldestMineableScn(connWithFloor(true, null)))
                .isNull();
    }

    @Test
    void queryFailuresReachTheCaller() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenThrow(new SQLException("ORA-00942"));

        assertThatThrownBy(() -> OracleLogMinerDialect.INSTANCE.oldestMineableScn(conn))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ORA-00942");
    }
}
