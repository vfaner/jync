package com.qqmu.jync.service.etl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.model.DatabaseType;

/**
 * The ETL resume key is stored as the source driver's text rendering of the last copied
 * key. For a zone-less temporal column that text is a wall clock, and binding it through
 * {@code Timestamp.valueOf} re-reads it in the JVM's default zone — a pre-1949 Shanghai
 * DST-gap time (1948-05-01 00:00, say) moves forward an hour, so the continuation query
 * skips every row in that hour.
 */
class JdbcEtlSourceResumeKeyTest {

    @Test
    void aWallClockResumeKeyBindsWithoutTheGapShift() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);

        JdbcEtlSource.bindResumeKey(ps, new EtlCheckpointValue(
                Types.TIMESTAMP, "1948-05-01 00:00:00"), DatabaseType.ORACLE);

        verify(ps).setTimestamp(eq(1), eq(Timestamp.from(wall.toInstant(ZoneOffset.UTC))),
                argThat(calendar -> calendar != null
                        && "UTC".equals(calendar.getTimeZone().getID())));
        verify(ps, never()).setObject(eq(1), any());
    }

    @Test
    void aWallClockResumeKeyKeepsItsFractionalSeconds() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        LocalDateTime wall = LocalDateTime.of(1947, 4, 15, 0, 0, 0, 123_456_000);

        JdbcEtlSource.bindResumeKey(ps, new EtlCheckpointValue(
                Types.TIMESTAMP, "1947-04-15 00:00:00.123456"), DatabaseType.ORACLE);

        verify(ps).setTimestamp(eq(1), eq(Timestamp.from(wall.toInstant(ZoneOffset.UTC))),
                any(java.util.Calendar.class));
    }

    @Test
    void anOrdinaryWallClockResumeKeyAlsoAvoidsTheShift() throws Exception {
        // Same binding for every wall-clock literal; only the parser differs from before.
        PreparedStatement ps = mock(PreparedStatement.class);
        LocalDateTime wall = LocalDateTime.of(2026, 11, 5, 10, 0);

        JdbcEtlSource.bindResumeKey(ps, new EtlCheckpointValue(
                Types.TIMESTAMP, "2026-11-05 10:00:00"), DatabaseType.ORACLE);

        verify(ps).setTimestamp(eq(1), eq(Timestamp.from(wall.toInstant(ZoneOffset.UTC))),
                any(java.util.Calendar.class));
    }

    @Test
    void aPostgresResumeKeyBindsWithoutTheGapShift() throws Exception {
        // PgJDBC converts a LocalDateTime through Timestamp.valueOf, so the resume key
        // needs the same fixed-offset calendar the Oracle source gets.
        PreparedStatement ps = mock(PreparedStatement.class);
        LocalDateTime wall = LocalDateTime.of(1948, 5, 1, 0, 0);

        JdbcEtlSource.bindResumeKey(ps, new EtlCheckpointValue(
                Types.TIMESTAMP, "1948-05-01 00:00:00"), DatabaseType.POSTGRESQL);

        verify(ps).setTimestamp(eq(1), eq(Timestamp.from(wall.toInstant(ZoneOffset.UTC))),
                argThat(calendar -> calendar != null
                        && "UTC".equals(calendar.getTimeZone().getID())));
    }

    @Test
    void aMySqlSourceStillBindsTheWallClockThroughSetObject() throws Exception {
        // Connector/J sends the LocalDateTime as the wall clock; no calendar is needed.
        PreparedStatement ps = mock(PreparedStatement.class);

        JdbcEtlSource.bindResumeKey(ps, new EtlCheckpointValue(
                Types.TIMESTAMP, "1948-05-01 00:00:00"), DatabaseType.MYSQL);

        verify(ps).setObject(1, LocalDateTime.of(1948, 5, 1, 0, 0));
    }

    @Test
    void aNonTemporalResumeKeyIsUnaffected() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        JdbcEtlSource.bindResumeKey(ps, new EtlCheckpointValue(Types.BIGINT, "42"),
                DatabaseType.ORACLE);

        verify(ps).setLong(1, 42L);
    }
}
