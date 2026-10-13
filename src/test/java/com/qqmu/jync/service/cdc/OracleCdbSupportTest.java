package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

/**
 * P0-2: Oracle CDB detection helpers. The mining-mode decision (single-account vs
 * common-user-from-CDB$ROOT) is only as good as these three queries, so each is pinned
 * against mocked JDBC — including the "query denied" degradation path, which must say
 * "indeterminate" instead of guessing non-CDB and later dying with ORA-65040.
 */
class OracleCdbSupportTest {

    private Connection connReturning(String queryContent, String value) throws SQLException {
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains(queryContent)) {
                when(rs.next()).thenReturn(true);
                when(rs.getString(1)).thenReturn(value);
                return rs;
            }
            throw new SQLException("ORA-00942: table or view does not exist");
        });
        return conn;
    }

    @Test
    void isCdbTrueOnYes() throws SQLException {
        assertThat(OracleCdbSupport.isCdb(connReturning("CDB FROM V$DATABASE", "YES")))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void isCdbFalseOnNo() throws SQLException {
        assertThat(OracleCdbSupport.isCdb(connReturning("CDB FROM V$DATABASE", "NO")))
                .isEqualTo(Boolean.FALSE);
    }

    @Test
    void isCdbNullWhenQueryDenied() throws SQLException {
        // V$DATABASE 权限缺失(11g 低配账号)→ 不可判,绝不默认 false 把 CDB 当非 CDB。
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(conn.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenThrow(new SQLException("ORA-00942"));
        assertThat(OracleCdbSupport.isCdb(conn)).isNull();
    }

    @Test
    void cdbNameReadsVDatabaseName() throws SQLException {
        assertThat(OracleCdbSupport.cdbName(connReturning("NAME FROM V$DATABASE", "FREE")))
                .isEqualTo("FREE");
    }

    @Test
    void currentConIdParsesUserenv() throws SQLException {
        assertThat(OracleCdbSupport.currentConId(
                connReturning("SYS_CONTEXT('USERENV', 'CON_ID')", "3"))).isEqualTo(3);
    }

    @Test
    void currentConIdNegativeWhenUnreadable() throws SQLException {
        Connection conn = mock(Connection.class);
        when(conn.createStatement()).thenThrow(new SQLException("dazed"));
        assertThat(OracleCdbSupport.currentConId(conn)).isEqualTo(-1);
    }

    @Test
    void rootServiceUrlIsThinServiceForm() {
        // CDB 名即 root service 名(Oracle 约定)——gvenzl/oracle-free: FREE/FREEPDB1。
        assertThat(OracleCdbSupport.rootServiceUrl("db.internal", 1521, "FREE"))
                .isEqualTo("jdbc:oracle:thin:@//db.internal:1521/FREE");
    }
}
