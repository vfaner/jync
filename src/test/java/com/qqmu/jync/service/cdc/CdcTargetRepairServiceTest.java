package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.SyncResult;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.model.SyncTargetKind;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.sync.SyncEngine;
import com.qqmu.jync.service.sync.SyncStateWriter;

/**
 * 目标表被删后的定向修复:只重建并重灌缺失的那几张表,位点不动,次数有上限。
 *
 * <p>This is the CDC counterpart of polling's ensure-target-table behaviour. It must never
 * turn into a project-wide re-baseline: a re-baseline clears every cursor and full-loads every
 * selected table, which is exactly what the user asked to avoid for one lost table.
 */
class CdcTargetRepairServiceTest {

    private static final long PROJECT_ID = 91L;

    private DataSourceManager dataSourceManager;
    private SyncEngine engine;
    private SyncStateWriter stateWriter;
    private CdcDdlTaskReconciler ddlTaskReconciler;
    private MetadataReader sourceReader;
    private SyncProperties properties;
    private CdcTargetRepairService service;
    private Connection sourceConn;
    private Connection targetConn;
    private final DatabaseConfig sourceCfg = new DatabaseConfig();
    private final DatabaseConfig targetCfg = new DatabaseConfig();
    private Project project;

    @BeforeEach
    void setUp() throws Exception {
        dataSourceManager = mock(DataSourceManager.class);
        engine = mock(SyncEngine.class);
        stateWriter = mock(SyncStateWriter.class);
        ddlTaskReconciler = mock(CdcDdlTaskReconciler.class);
        sourceReader = mock(MetadataReader.class);
        properties = new SyncProperties();
        service = new CdcTargetRepairService(dataSourceManager, engine, stateWriter,
                ddlTaskReconciler, properties);

        sourceConn = DriverManager.getConnection("jdbc:h2:mem:repair_service_src", "sa", "");
        targetConn = DriverManager.getConnection("jdbc:h2:mem:repair_service_tgt", "sa", "");
        // A pool hands out a borrow, and the service closes it. Return a fresh connection per
        // call; the in-memory database survives because the fixture's own connections stay open.
        when(dataSourceManager.getConnection(sourceCfg)).thenAnswer(inv -> DriverManager
                .getConnection("jdbc:h2:mem:repair_service_src", "sa", ""));
        when(dataSourceManager.getConnection(targetCfg)).thenAnswer(inv -> DriverManager
                .getConnection("jdbc:h2:mem:repair_service_tgt", "sa", ""));
        when(sourceReader.listTableNames(any(), any())).thenReturn(List.of("ORDERS", "CUSTOMERS"));

        project = new Project();
        project.setId(PROJECT_ID);
        project.setName("targeted-repair");

        SyncResult success = new SyncResult();
        success.setRowsInserted(12);
        when(engine.runCycle(any())).thenReturn(success);
    }

    @AfterEach
    void tearDown() throws Exception {
        sourceConn.close();
        targetConn.close();
    }

    @Test
    void repairsOnlyTheMissingTargetTables() throws Exception {
        createTable(targetConn, "CUSTOMERS");
        when(engine.runCycle(any())).thenAnswer(inv -> {
            createTable(targetConn, "ORDERS");
            SyncResult repaired = new SyncResult();
            repaired.setRowsInserted(12);
            return repaired;
        });

        CdcTargetRepairService.Outcome outcome = service.repair(ctx(), missing("ORDERS"));

        assertThat(outcome.status()).isEqualTo(CdcTargetRepairService.Status.REPAIRED);
        assertThat(outcome.repairedTables()).containsExactly("ORDERS");

        ArgumentCaptor<SyncContext> used = ArgumentCaptor.forClass(SyncContext.class);
        verify(engine).runCycle(used.capture());
        assertThat(used.getValue().getRepairTables()).containsExactly("ORDERS");
        assertThat(used.getValue().isCdcRepairRun()).isTrue();
    }

    @Test
    void treatsTheTableNamedByTheFaultAsMissingEvenIfSomeoneRecreatedIt() throws Exception {
        // The stream saw the table vanish moments ago; a table that exists again now holds no
        // rows. It is repaired (reloaded), so it is named even though the probe says present.
        createTable(targetConn, "ORDERS");
        createTable(targetConn, "CUSTOMERS");
        when(engine.runCycle(any())).thenReturn(new SyncResult());

        CdcTargetRepairService.Outcome outcome = service.repair(ctx(), missing("ORDERS"));

        assertThat(outcome.status()).isEqualTo(CdcTargetRepairService.Status.REPAIRED);
        ArgumentCaptor<SyncContext> used = ArgumentCaptor.forClass(SyncContext.class);
        verify(engine).runCycle(used.capture());
        assertThat(used.getValue().getRepairTables()).contains("ORDERS");
    }

    @Test
    void repairsEveryMissingTableOfTheProjectInOneCycle() throws Exception {
        when(engine.runCycle(any())).thenAnswer(inv -> {
            createTable(targetConn, "ORDERS");
            createTable(targetConn, "CUSTOMERS");
            return new SyncResult();
        });

        CdcTargetRepairService.Outcome outcome = service.repair(ctx(), missing("ORDERS"));

        assertThat(outcome.repairedTables()).containsExactlyInAnyOrder("ORDERS", "CUSTOMERS");
    }

    @Test
    void doesNothingWhenTheSourceTableIsGone() throws Exception {
        when(sourceReader.listTableNames(any(), any())).thenReturn(List.of("CUSTOMERS"));

        CdcTargetRepairService.Outcome outcome = service.repair(ctx(), missing("ORDERS"));

        assertThat(outcome.status()).isEqualTo(CdcTargetRepairService.Status.NOT_APPLICABLE);
        verify(engine, never()).runCycle(any());
    }

    @Test
    void isSwitchedOffByConfiguration() throws Exception {
        properties.getCdc().setTargetTableRepairEnabled(false);

        CdcTargetRepairService.Outcome outcome = service.repair(ctx(), missing("ORDERS"));

        assertThat(outcome.status()).isEqualTo(CdcTargetRepairService.Status.DISABLED);
        verify(engine, never()).runCycle(any());
    }

    @Test
    void parksAfterTheConfiguredNumberOfAttemptsAndAHealthyTransactionReopensTheBudget()
            throws Exception {
        properties.getCdc().setTargetTableRepairMaxAttempts(2);
        doAnswer(inv -> {
            createTable(targetConn, "ORDERS");
            createTable(targetConn, "CUSTOMERS");
            return new SyncResult();
        }).when(engine).runCycle(any());

        assertThat(service.repair(ctx(), missing("ORDERS")).status())
                .isEqualTo(CdcTargetRepairService.Status.REPAIRED);
        assertThat(service.repair(ctx(), missing("ORDERS")).status())
                .isEqualTo(CdcTargetRepairService.Status.REPAIRED);
        CdcTargetRepairService.Outcome capped = service.repair(ctx(), missing("ORDERS"));
        assertThat(capped.status()).isEqualTo(CdcTargetRepairService.Status.CAP_REACHED);
        assertThat(capped.detail()).contains("2");
        verify(engine, org.mockito.Mockito.times(2)).runCycle(any());

        // A transaction finally applied: the incident is over, a later drop gets a fresh budget.
        service.resetAttempts(PROJECT_ID);
        assertThat(service.repair(ctx(), missing("ORDERS")).status())
                .isEqualTo(CdcTargetRepairService.Status.REPAIRED);
    }

    @Test
    void failsWhenTheTargetStillLacksTheTableAfterTheCycle() throws Exception {
        when(engine.runCycle(any())).thenReturn(new SyncResult());

        CdcTargetRepairService.Outcome outcome = service.repair(ctx(), missing("ORDERS"));

        assertThat(outcome.status()).isEqualTo(CdcTargetRepairService.Status.FAILED);
        assertThat(outcome.detail()).contains("ORDERS");
    }

    @Test
    void failsWhenTheCycleReportsAnError() throws Exception {
        when(engine.runCycle(any())).thenAnswer(inv -> {
            SyncResult failed = new SyncResult();
            failed.addError("Failed to create target table ORDERS: permission denied");
            return failed;
        });

        CdcTargetRepairService.Outcome outcome = service.repair(ctx(), missing("ORDERS"));

        assertThat(outcome.status()).isEqualTo(CdcTargetRepairService.Status.FAILED);
        assertThat(outcome.detail()).contains("permission denied");
    }

    @Test
    void recordsTheRepairForTheChangeLogAndTheAlert() throws Exception {
        when(engine.runCycle(any())).thenAnswer(inv -> {
            createTable(targetConn, "ORDERS");
            createTable(targetConn, "CUSTOMERS");
            SyncResult repaired = new SyncResult();
            repaired.setRowsInserted(12);
            return repaired;
        });

        service.repair(ctx(), missing("ORDERS"));

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.TABLE), eq("ORDERS"),
                eq(ChangeType.INFO), details.capture(), eq(true), anyLong(), eq(12));
        assertThat(details.getValue())
                .startsWith(CdcTargetRepairService.REPAIR_LOG_MARKER)
                .contains("ORDERS")
                .contains("12");
    }

    @Test
    void closesTheOpenDdlTasksOfTheRebuiltTables() throws Exception {
        when(engine.runCycle(any())).thenAnswer(inv -> {
            createTable(targetConn, "ORDERS");
            createTable(targetConn, "CUSTOMERS");
            return new SyncResult();
        });
        when(ddlTaskReconciler.resolveAfterTargetRebuild(eq(PROJECT_ID), any())).thenReturn(2);

        service.repair(ctx(), missing("ORDERS"));

        verify(ddlTaskReconciler).resolveAfterTargetRebuild(eq(PROJECT_ID),
                argThat(tables -> tables.contains("ORDERS") && tables.contains("CUSTOMERS")));
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(stateWriter).recordLog(eq(PROJECT_ID), eq(ObjectType.TABLE), eq("ORDERS"),
                eq(ChangeType.INFO), details.capture(), eq(true), anyLong(), any());
        assertThat(details.getValue()).contains("closed 2 open skipped-DDL task");
    }

    @Test
    void aFailedRepairLeavesTheDdlTasksOpen() throws Exception {
        // The target still lacks the table after the cycle: the task is still real work.
        when(engine.runCycle(any())).thenReturn(new SyncResult());

        assertThat(service.repair(ctx(), missing("ORDERS")).status())
                .isEqualTo(CdcTargetRepairService.Status.FAILED);
        verify(ddlTaskReconciler, never()).resolveAfterTargetRebuild(any(), any());
    }

    @Test
    void aRedisTargetIsNeverRepaired() throws Exception {
        project.setTargetKind(SyncTargetKind.REDIS);

        CdcTargetRepairService.Outcome outcome = service.repair(ctx(), missing("ORDERS"));

        assertThat(outcome.status()).isEqualTo(CdcTargetRepairService.Status.NOT_APPLICABLE);
        verify(engine, never()).runCycle(any());
    }

    @Test
    void aFailureThatNamesNoTableIsNotApplicable() {
        CdcTargetRepairService.Outcome outcome = service.repair(ctx(),
                new SQLException("some other failure"));

        assertThat(outcome.status()).isEqualTo(CdcTargetRepairService.Status.NOT_APPLICABLE);
    }

    private static CdcTargetTableMissingException missing(String table) {
        return new CdcTargetTableMissingException(table, table, null,
                new SQLException("Table \"" + table + "\" not found"));
    }

    private static void createTable(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + name + " (ID BIGINT NOT NULL PRIMARY KEY)");
        }
    }

    private SyncContext ctx() {
        SyncConfig config = new SyncConfig();
        config.setSyncData(true);
        config.setSyncDeletes(true);
        return SyncContext.builder()
                .project(project)
                .config(config)
                .sourceConfig(sourceCfg)
                .targetConfig(targetCfg)
                .sourceType(DatabaseType.MYSQL)
                .targetType(DatabaseType.MYSQL)
                .sourceReader(sourceReader)
                .sourceDialect(new GenericSqlDialect())
                .targetDialect(new GenericSqlDialect())
                .batchSize(50)
                .build();
    }
}
