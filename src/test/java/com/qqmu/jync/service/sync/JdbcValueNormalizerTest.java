package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * Vendor values must leave the driver as portable Java values.
 *
 * <p>{@code getObject} returns driver-private classes for SQL Server's {@code DateTimeOffset},
 * Oracle's {@code XMLTYPE} and every geometry type; no target driver can bind those. The
 * normalizer reads the payload a portable target column can store, and stays out of the way
 * for standard types so the regular getter choice still applies.
 */
class JdbcValueNormalizerTest {

    private final ResultSet rs = mock(ResultSet.class);

    @Test
    void uniqueIdentifierReadsAsText() throws SQLException {
        when(rs.getString(1)).thenReturn("0f8fad5b-d9cb-469f-a165-70867728950e");
        assertThat(JdbcValueNormalizer.read(rs, 1, -145, "uniqueidentifier",
                DatabaseType.SQLSERVER)).isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    @Test
    void xmlTypeReadsAsText() throws SQLException {
        when(rs.getString(1)).thenReturn("<a/>");
        assertThat(JdbcValueNormalizer.read(rs, 1, 2007, "XMLTYPE", DatabaseType.ORACLE))
                .isEqualTo("<a/>");
        assertThat(JdbcValueNormalizer.read(rs, 1, -152, "xml", DatabaseType.SQLSERVER))
                .isEqualTo("<a/>");
    }

    @Test
    void intervalReadsAsText() throws SQLException {
        when(rs.getString(1)).thenReturn("+01-02");
        assertThat(JdbcValueNormalizer.read(rs, 1, -103, "INTERVALYM", DatabaseType.ORACLE))
                .isEqualTo("+01-02");
    }

    @Test
    void rowIdReadsAsText() throws SQLException {
        when(rs.getString(1)).thenReturn("AAAEMUAAJAAABcEAAA");
        assertThat(JdbcValueNormalizer.read(rs, 1, Types.ROWID, "ROWID", DatabaseType.ORACLE))
                .isEqualTo("AAAEMUAAJAAABcEAAA");
    }

    @Test
    void moneyReadsAsBigDecimal() throws SQLException {
        when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("12.34"));
        assertThat(JdbcValueNormalizer.read(rs, 1, -148, "money", DatabaseType.SQLSERVER))
                .isEqualTo(new BigDecimal("12.34"));
        assertThat(JdbcValueNormalizer.read(rs, 1, -100001, "DECFLOAT", DatabaseType.DB2))
                .isEqualTo(new BigDecimal("12.34"));
    }

    @Test
    void geometryReadsAsWkbBytes() throws SQLException {
        byte[] wkb = {1, 2, 3};
        when(rs.getBytes(1)).thenReturn(wkb);
        assertThat(JdbcValueNormalizer.read(rs, 1, -157, "geometry", DatabaseType.SQLSERVER))
                .isSameAs(wkb);
        assertThat(JdbcValueNormalizer.read(rs, 1, Types.BINARY, "GEOMETRY",
                DatabaseType.MYSQL)).isSameAs(wkb);
    }

    @Test
    void dateTimeOffsetKeepsItsOffset() throws SQLException {
        OffsetDateTime value = OffsetDateTime.of(2026, 10, 22, 8, 30, 0, 0, ZoneOffset.ofHours(8));
        when(rs.getObject(1, OffsetDateTime.class)).thenReturn(value);
        assertThat(JdbcValueNormalizer.read(rs, 1, -155, "datetimeoffset",
                DatabaseType.SQLSERVER)).isEqualTo(value);
    }

    @Test
    void dateTimeOffsetFallsBackToDriverText() throws SQLException {
        when(rs.getObject(1, OffsetDateTime.class))
                .thenThrow(new SQLException("not supported"));
        when(rs.getString(1)).thenReturn("2026-10-22 08:30:00 +08:00");
        assertThat(JdbcValueNormalizer.read(rs, 1, -155, "datetimeoffset",
                DatabaseType.SQLSERVER)).isEqualTo("2026-10-22 08:30:00 +08:00");
    }

    @Test
    void standardTypesStayUnhandled() throws SQLException {
        assertThat(JdbcValueNormalizer.read(rs, 1, Types.VARCHAR, "VARCHAR",
                DatabaseType.MYSQL)).isSameAs(JdbcValueNormalizer.UNHANDLED);
        assertThat(JdbcValueNormalizer.read(rs, 1, Types.CLOB, "CLOB",
                DatabaseType.ORACLE)).isSameAs(JdbcValueNormalizer.UNHANDLED);
        assertThat(JdbcValueNormalizer.read(rs, 1, Types.VARCHAR, null,
                DatabaseType.MYSQL)).isSameAs(JdbcValueNormalizer.UNHANDLED);
        verify(rs, never()).getString(anyInt());
    }
}
