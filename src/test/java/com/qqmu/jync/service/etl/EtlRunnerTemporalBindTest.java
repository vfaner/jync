package com.qqmu.jync.service.etl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.PostgresDialect;

/**
 * The copy loop binds each extracted value with the target driver's zone semantics. Oracle's
 * driver renders a zone-less {@code LocalDateTime} through the JVM default zone, which moves a
 * historical DST-gap wall clock forward an hour; the batch writer must go through the fixed
 * offset calendar instead. The rest of the columns keep the plain bind they already had.
 */
class EtlRunnerTemporalBindTest {

    private final PreparedStatement insert = mock(PreparedStatement.class);

    @Test
    void aWallClockIsBoundToAnOracleTargetThroughTheFixedOffsetCalendar() throws SQLException {
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);

        EtlRunner.bindRow(insert, columns(Types.TIMESTAMP, "DATE"), new Object[] {wall},
                new OracleDialect());

        verify(insert).setTimestamp(eq(1),
                eq(Timestamp.from(wall.toInstant(ZoneOffset.UTC))),
                argThat(calendar -> "UTC".equals(calendar.getTimeZone().getID())));
        verify(insert, never()).setObject(anyInt(), any());
    }

    @Test
    void aWallClockIsBoundToAPostgresTargetThroughTheFixedOffsetCalendar() throws SQLException {
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);

        EtlRunner.bindRow(insert, columns(Types.TIMESTAMP, "TIMESTAMP"), new Object[] {wall},
                new PostgresDialect());

        verify(insert).setTimestamp(eq(1),
                eq(Timestamp.from(wall.toInstant(ZoneOffset.UTC))),
                argThat(calendar -> "UTC".equals(calendar.getTimeZone().getID())));
        verify(insert, never()).setObject(anyInt(), any());
    }

    @Test
    void aWallClockToAMySqlTargetKeepsThePlainObjectBind() throws SQLException {
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);

        EtlRunner.bindRow(insert, columns(Types.TIMESTAMP, "DATETIME"), new Object[] {wall},
                new MySqlDialect());

        verify(insert).setObject(1, wall);
        verify(insert, never()).setTimestamp(anyInt(), any(), any());
    }

    @Test
    void aNonTemporalValueIsBoundWithThePlainObjectBind() throws SQLException {
        EtlRunner.bindRow(insert, List.of(
                new EtlSourceColumn("id", Types.INTEGER, "INTEGER", null, null, true)),
                new Object[] {42L}, new OracleDialect());

        verify(insert).setObject(1, 42L);
    }

    @Test
    void aNullUsesSetNullWithTheColumnType() throws SQLException {
        EtlRunner.bindRow(insert, columns(Types.TIMESTAMP, "DATE"), new Object[] {null},
                new OracleDialect());

        verify(insert).setNull(1, Types.TIMESTAMP);
        verify(insert, never()).setObject(anyInt(), any());
    }

    private static List<EtlSourceColumn> columns(int jdbcType, String typeName) {
        return List.of(new EtlSourceColumn("d", jdbcType, typeName, null, null, true));
    }
}
