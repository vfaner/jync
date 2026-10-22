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
import com.qqmu.jync.service.converter.MySqlDialect;
import com.qqmu.jync.service.converter.OpenGaussDialect;
import com.qqmu.jync.service.converter.OracleDialect;
import com.qqmu.jync.service.converter.PostgresDialect;
import com.qqmu.jync.service.converter.SqlDialect;
import com.qqmu.jync.service.converter.SqlServerDialect;

/**
 * The initial load may write a plain {@code INSERT} instead of the dialect's upsert when the
 * target table is still empty — on an empty table nothing can match, so the upsert's match
 * test buys nothing while costing per-row work (the Oracle benchmark measured INSERT at
 * roughly 2.5x the MERGE throughput).
 *
 * <p>The decision is a one-row probe, and every way the probe can be wrong has to land on the
 * upsert: a table with rows, a target whose dialect has no such shortcut, a keyless table
 * (already INSERT-only), the switch turned off, or a probe that simply fails. INSERT into a
 * non-empty table raises a unique violation, so guessing "empty" is not a harmless mistake.
 */
class InitialLoadInsertFirstProbeTest {

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
        assertThat(useFirst(target, DatabaseType.ORACLE, new OracleDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();

        assertThat(probedSql())
                .isEqualTo("SELECT 1 FROM \"APP\".\"T_ORDERS\" WHERE ROWNUM = 1");
    }

    @Test
    void anEmptyMySqlTargetIsLoadedWithInsert() throws SQLException {
        assertThat(useFirst(target, DatabaseType.MYSQL, new MySqlDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();

        assertThat(probedSql()).isEqualTo("SELECT 1 FROM `APP`.`T_ORDERS` LIMIT 1");
    }

    @Test
    void anEmptyPostgresTargetIsLoadedWithInsert() throws SQLException {
        assertThat(useFirst(target, DatabaseType.POSTGRESQL, new PostgresDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();

        assertThat(probedSql()).isEqualTo("SELECT 1 FROM \"APP\".\"T_ORDERS\" LIMIT 1");
    }

    @Test
    void anEmptyOpenGaussTargetIsLoadedWithInsert() throws SQLException {
        // openGauss shares the PostgreSQL family and inherits the same probe.
        assertThat(useFirst(target, DatabaseType.OPENGAUSS, new OpenGaussDialect(),
                "T_ORDERS", List.of("ID"))).isTrue();

        assertThat(probedSql()).isEqualTo("SELECT 1 FROM \"APP\".\"T_ORDERS\" LIMIT 1");
    }

    @Test
    void aTargetThatAlreadyHasRowsStaysOnTheUpsert() throws SQLException {
        when(probe.next()).thenReturn(true);
        assertThat(useFirst(target, DatabaseType.MYSQL, new MySqlDialect(),
                "T_ORDERS", List.of("ID"))).isFalse();
    }

    @Test
    void aProbeThatFailsFallsBackToTheUpsert() throws SQLException {
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("ORA-00942"));
        assertThat(useFirst(target, DatabaseType.ORACLE, new OracleDialect(),
                "T_ORDERS", List.of("ID"))).isFalse();
    }

    @Test
    void everyOtherTargetKeepsItsOwnUpsert() throws SQLException {
        assertThat(useFirst(target, DatabaseType.SQLSERVER, new SqlServerDialect(),
                "T_ORDERS", List.of("ID"))).isFalse();
        verify(target, never()).createStatement();
    }

    @Test
    void theSwitchTurnsThePathOffWithoutProbing() throws SQLException {
        SyncProperties properties = new SyncProperties();
        properties.setInitialLoadInsertFirst(false);
        DataSyncService disabled = new DataSyncService(properties);

        assertThat(disabled.useInsertFirst(target, context(DatabaseType.MYSQL,
                new MySqlDialect()), "T_ORDERS", List.of("ID"))).isFalse();
        verify(target, never()).createStatement();
    }

    @Test
    void theOldOracleOnlySwitchStillBinds() throws SQLException {
        SyncProperties properties = new SyncProperties();
        properties.setOracleInitialLoadInsertFirst(false);
        DataSyncService disabled = new DataSyncService(properties);

        assertThat(properties.isInitialLoadInsertFirst()).isFalse();
        assertThat(disabled.useInsertFirst(target, context(DatabaseType.MYSQL,
                new MySqlDialect()), "T_ORDERS", List.of("ID"))).isFalse();
        verify(target, never()).createStatement();
    }

    @Test
    void aKeylessTableIsAlreadyInsertOnly() throws SQLException {
        assertThat(useFirst(target, DatabaseType.POSTGRESQL, new PostgresDialect(),
                "T_ORDERS", List.of())).isFalse();
        verify(target, never()).createStatement();
    }

    private String probedSql() throws SQLException {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).executeQuery(sql.capture());
        return sql.getValue();
    }

    private boolean useFirst(Connection conn, DatabaseType targetType, SqlDialect dialect,
                             String table, List<String> pkColumns) {
        return service.useInsertFirst(conn, context(targetType, dialect), table, pkColumns);
    }

    private static SyncContext context(DatabaseType targetType, SqlDialect dialect) {
        return SyncContext.builder()
                .config(new SyncConfig())
                .targetType(targetType)
                .targetDialect(dialect)
                .targetSchema("APP")
                .batchSize(50)
                .build();
    }
}
