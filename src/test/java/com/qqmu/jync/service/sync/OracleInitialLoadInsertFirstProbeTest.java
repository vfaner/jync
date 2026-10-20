package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.converter.OracleDialect;

/**
 * The initial load may write a plain {@code INSERT} instead of a {@code MERGE} when the Oracle
 * target table is still empty — the benchmark measured INSERT at roughly 2.5x the MERGE
 * throughput, and on an empty table the two are equally idempotent because nothing can match.
 *
 * <p>The decision is a one-row probe, and every way the probe can be wrong has to land on
 * MERGE: a table with rows, a target that is not Oracle, a keyless table (already INSERT-only),
 * the switch turned off, or a probe that simply fails. INSERT into a non-empty table raises a
 * unique violation, so guessing "empty" is not a harmless mistake.
 */
class OracleInitialLoadInsertFirstProbeTest {

    private Connection target;
    private Statement statement;
    private ResultSet probe;
    private DataSyncService service;

    @BeforeEach
    void setUp() throws SQLException {
        target = mock(Connection.class);
        statement = mock(Statement.class);
        probe = mock(ResultSet.class);
        when(target.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(probe);
        when(probe.next()).thenReturn(false);
        service = new DataSyncService(new SyncProperties());
    }

    @Test
    void anEmptyOracleTargetIsLoadedWithInsert() throws SQLException {
        assertThat(useFirst(target, DatabaseType.ORACLE, "T_ORDERS", List.of("ID"))).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).executeQuery(sql.capture());
        assertThat(sql.getValue())
                .isEqualTo("SELECT 1 FROM \"APP\".\"T_ORDERS\" WHERE ROWNUM = 1");
    }

    @Test
    void aTargetThatAlreadyHasRowsStaysOnMerge() throws SQLException {
        when(probe.next()).thenReturn(true);
        assertThat(useFirst(target, DatabaseType.ORACLE, "T_ORDERS", List.of("ID"))).isFalse();
    }

    @Test
    void aProbeThatFailsFallsBackToMerge() throws SQLException {
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("ORA-00942"));
        assertThat(useFirst(target, DatabaseType.ORACLE, "T_ORDERS", List.of("ID"))).isFalse();
    }

    @Test
    void everyOtherTargetKeepsItsOwnUpsert() throws SQLException {
        assertThat(useFirst(target, DatabaseType.POSTGRESQL, "T_ORDERS", List.of("ID"))).isFalse();
        verify(target, never()).createStatement();
    }

    @Test
    void theSwitchTurnsThePathOffWithoutProbing() throws SQLException {
        SyncProperties properties = new SyncProperties();
        properties.setOracleInitialLoadInsertFirst(false);
        DataSyncService disabled = new DataSyncService(properties);

        assertThat(disabled.useInsertFirst(target, context(DatabaseType.ORACLE),
                "T_ORDERS", List.of("ID"))).isFalse();
        verify(target, never()).createStatement();
    }

    @Test
    void aKeylessTableIsAlreadyInsertOnly() throws SQLException {
        assertThat(useFirst(target, DatabaseType.ORACLE, "T_ORDERS", List.of())).isFalse();
        verify(target, never()).createStatement();
    }

    private boolean useFirst(Connection conn, DatabaseType targetType, String table,
                             List<String> pkColumns) {
        return service.useInsertFirst(conn, context(targetType), table, pkColumns);
    }

    private static SyncContext context(DatabaseType targetType) {
        return SyncContext.builder()
                .config(new SyncConfig())
                .targetType(targetType)
                .targetDialect(new OracleDialect())
                .targetSchema("APP")
                .batchSize(50)
                .build();
    }
}
