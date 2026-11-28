package com.qqmu.jync.service.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.dto.meta.ColumnMeta;
import com.qqmu.jync.dto.meta.TableMeta;
import com.qqmu.jync.model.CdcPosition;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.repository.CdcPositionRepository;
import com.qqmu.jync.repository.ChangeLogRepository;
import com.qqmu.jync.repository.ProjectRepository;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.metadata.MetadataReader;
import com.qqmu.jync.service.sync.SyncContext;
import com.qqmu.jync.service.task.SyncContextFactory;

@ExtendWith(MockitoExtension.class)
class ExternalBaselineVerificationServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private SyncContextFactory contextFactory;
    @Mock private DataSourceManager dataSourceManager;
    @Mock private CdcPositionStore positionStore;
    @Mock private CdcPositionRepository positionRepository;
    @Mock private ChangeLogRepository changeLogRepository;

    private ExternalBaselineVerificationService service;
    private Connection source;
    private Connection target;
    private Project project;
    private CdcPosition position;
    private DatabaseConfig sourceConfig;
    private DatabaseConfig targetConfig;

    @BeforeEach
    void setUp() throws Exception {
        service = new ExternalBaselineVerificationService(projectRepository, contextFactory,
                dataSourceManager, positionStore, positionRepository, changeLogRepository);
        source = DriverManager.getConnection("jdbc:h2:mem:baseline_verify", "sa", "");
        target = DriverManager.getConnection("jdbc:h2:mem:baseline_verify", "sa", "");
        try (Statement st = target.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))");
        }
        project = new Project();
        project.setId(1L);
        project.setName("orders-sync");
        project.setSourceDbId(10L);
        project.setTargetDbId(20L);
        sourceConfig = new DatabaseConfig();
        sourceConfig.setId(10L);
        targetConfig = new DatabaseConfig();
        targetConfig.setId(20L);
        position = new CdcPosition();
        position.setProjectId(1L);
        position.setBaselineDone(true);
        position.setExternalBaselineTableNames(List.of("ORDERS"));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        lenient().when(positionStore.find(1L)).thenReturn(Optional.of(position));
        lenient().when(contextFactory.parseConfig(project)).thenReturn(new SyncConfig());
        lenient().when(dataSourceManager.getConnection(sourceConfig)).thenReturn(source);
        lenient().when(dataSourceManager.getConnection(targetConfig)).thenReturn(target);
    }

    @AfterEach
    void tearDown() throws Exception {
        source.close();
        target.close();
    }

    @Test
    void recordsOkWhenEveryCoveredTableHasACompatibleTargetShape() {
        SyncContext ctx = context();
        when(contextFactory.build(project, "baseline-verify", null)).thenReturn(ctx);

        service.verifyNow(1L);

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(positionStore).markExternalBaselineVerification(eq(1L), status.capture(), eq(0),
                any(), any());
        assertThat(status.getValue()).isEqualTo(CdcPosition.VERIFY_OK);
        verify(changeLogRepository).save(any(ChangeLog.class));
    }

    @Test
    void aChangeLogFailureDoesNotOverwriteASuccessfulStatus() {
        SyncContext ctx = context();
        when(contextFactory.build(project, "baseline-verify", null)).thenReturn(ctx);
        when(changeLogRepository.save(any(ChangeLog.class)))
                .thenThrow(new RuntimeException("change log unavailable"));

        service.verifyNow(1L);

        verify(positionStore).markExternalBaselineVerification(eq(1L),
                eq(CdcPosition.VERIFY_OK), eq(0), any(), any());
    }

    @Test
    void recordsMismatchWhenTheTargetTableIsMissing() throws Exception {
        try (Statement st = target.createStatement()) {
            st.execute("DROP TABLE ORDERS");
        }
        SyncContext ctx = context();
        when(contextFactory.build(project, "baseline-verify", null)).thenReturn(ctx);

        service.verifyNow(1L);

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(positionStore).markExternalBaselineVerification(eq(1L), status.capture(),
                eq(1), any(), any());
        assertThat(status.getValue()).isEqualTo(CdcPosition.VERIFY_MISMATCH);
    }

    @Test
    void recordsErrorWhenTheMetadataContextCannotBeBuilt() {
        when(contextFactory.build(project, "baseline-verify", null))
                .thenThrow(new IllegalStateException("source unreachable"));

        service.verifyNow(1L);

        verify(positionStore).markExternalBaselineVerification(eq(1L),
                eq(CdcPosition.VERIFY_ERROR), eq(1), any(), any());
        verify(changeLogRepository).save(any(ChangeLog.class));
    }

    @Test
    void recoversAStaleRunningCheckToPending() {
        CdcPosition stale = new CdcPosition();
        stale.setProjectId(2L);
        when(positionRepository
                .findByExternalBaselineVerifyStatusAndExternalBaselineVerifiedAtBefore(
                        eq(CdcPosition.VERIFY_RUNNING), any()))
                .thenReturn(List.of(stale));

        service.recoverStaleRuns();

        verify(positionStore).markExternalBaselineVerification(eq(2L),
                eq(CdcPosition.VERIFY_PENDING), isNull(), isNull(), isNull());
    }

    private SyncContext context() {
        SyncContext ctx = mock(SyncContext.class);
        MetadataReader reader = mock(MetadataReader.class);
        TableMeta table = new TableMeta();
        table.setName("ORDERS");
        ColumnMeta id = new ColumnMeta();
        id.setName("ID");
        id.setJdbcType(Types.BIGINT);
        ColumnMeta name = new ColumnMeta();
        name.setName("NAME");
        name.setJdbcType(Types.VARCHAR);
        table.setColumns(List.of(id, name));
        try {
            when(reader.readTable(source, "PUBLIC", "ORDERS")).thenReturn(table);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(ctx.getSourceConfig()).thenReturn(sourceConfig);
        when(ctx.getTargetConfig()).thenReturn(targetConfig);
        when(ctx.getSourceSchema()).thenReturn("PUBLIC");
        when(ctx.getTargetSchema()).thenReturn("PUBLIC");
        when(ctx.getSourceReader()).thenReturn(reader);
        when(ctx.getConfig()).thenReturn(new SyncConfig());
        when(ctx.effectiveTargetType()).thenReturn(DatabaseType.H2);
        when(ctx.targetPreservesIdentifierCase()).thenReturn(false);
        return ctx;
    }
}
