package com.qqmu.jync.service.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.SqlDialect;

/**
 * Oracle's {@code MERGE INTO t USING (SELECT ? AS c FROM DUAL)} binds a text placeholder as
 * a LONG, whose 32767-byte ceiling raises ORA-01461 for a value that fits the CLOB column
 * perfectly well. No driver-level convenience fixes this — setStringForClob,
 * setCharacterStream and setObject(..., Types.CLOB) all fail the same way — so anything
 * above the ceiling (measured in UTF-8 bytes, which over-estimates multibyte server
 * charsets and so errs safe) must go through a temporary CLOB created on the connection.
 * An empty string into a CLOB must also use one: Oracle binds '' as NULL, which a NOT NULL
 * CLOB column rejects.
 */
class JdbcRowMapperLobBindTest {

    private final PreparedStatement ps = mock(PreparedStatement.class);
    private final Connection conn = mock(Connection.class);
    private final Clob clob = mock(Clob.class);
    private final Blob blob = mock(Blob.class);

    private void bindWith(SqlDialect dialect, Object value, int targetType) throws Exception {
        when(ps.getConnection()).thenReturn(conn);
        when(conn.createClob()).thenReturn(clob);
        when(conn.createBlob()).thenReturn(blob);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("body", value);
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("body", targetType);
        JdbcRowMapper.bind(ps, row, List.of("body"), types, dialect);
    }

    @Test
    void oracleLargeCjkStringIsBoundAsTempClob() throws Exception {
        String big = "中".repeat(20000); // 60000 UTF-8 bytes
        bindWith(new OracleDialect(), big, Types.CLOB);
        verify(conn).createClob();
        verify(clob).setString(1, big);
        verify(ps).setClob(1, clob);
        verify(ps, never()).setObject(eq(1), any());
    }

    @Test
    void oracleStringOfExactly32767BytesStaysInline() throws Exception {
        String atCap = "x".repeat(32767);
        bindWith(new OracleDialect(), atCap, Types.LONGVARCHAR);
        verify(ps).setObject(1, atCap);
        verify(conn, never()).createClob();
    }

    @Test
    void oracleCjkStringJustOverTheByteCeilingUsesTempClob() throws Exception {
        // 10923 * 3 = 32769 bytes, although only 10923 characters.
        String over = "中".repeat(10923);
        bindWith(new OracleDialect(), over, Types.CLOB);
        verify(ps).setClob(1, clob);
    }

    @Test
    void oracleCjkStringJustUnderTheByteCeilingStaysInline() throws Exception {
        String under = "中".repeat(10922); // 32766 bytes
        bindWith(new OracleDialect(), under, Types.CLOB);
        verify(ps).setObject(1, under);
        verify(conn, never()).createClob();
    }

    @Test
    void oracleEmptyStringIntoLobTargetBindsAnEmptyClob() throws Exception {
        bindWith(new OracleDialect(), "", Types.CLOB);
        verify(conn).createClob();
        verify(clob).setString(1, "");
        verify(ps).setClob(1, clob);
    }

    @Test
    void oracleEmptyStringIntoVarcharTargetKeepsOracleSemantics() throws Exception {
        bindWith(new OracleDialect(), "", Types.VARCHAR);
        verify(ps).setObject(1, "");
        verify(conn, never()).createClob();
    }

    @Test
    void oracleLargeBinaryIsBoundAsTempBlob() throws Exception {
        byte[] bytes = new byte[40000];
        bindWith(new OracleDialect(), bytes, Types.BLOB);
        verify(conn).createBlob();
        verify(blob).setBytes(1, bytes);
        verify(ps).setBlob(1, blob);
        verify(ps, never()).setBytes(eq(1), any(byte[].class));
    }

    @Test
    void oracleSmallBinaryStaysSetBytes() throws Exception {
        byte[] bytes = {1, 2, 3};
        bindWith(new OracleDialect(), bytes, Types.VARBINARY);
        verify(ps).setBytes(1, bytes);
        verify(conn, never()).createBlob();
    }

    @Test
    void nonOracleLargeStringStillBindsWithSetObject() throws Exception {
        String big = "中".repeat(20000);
        bindWith(new MySqlDialect(), big, Types.LONGVARCHAR);
        verify(ps).setObject(1, big);
        verify(conn, never()).createClob();
    }
}
