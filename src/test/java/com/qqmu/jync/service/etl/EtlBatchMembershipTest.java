package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlBatch;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.repository.EtlBatchRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;
import com.qqmu.jync.service.converter.GenericSqlDialect;
import com.qqmu.jync.service.converter.SqlDialectFactory;
import com.qqmu.jync.service.metadata.ConnectionCatalogService;

/**
 * Building a batch from a source's table list. The server re-reads the catalog for every POST
 * instead of trusting the posted names, and every created member carries a default extraction
 * SQL identical to what an operator would have typed by hand.
 */
class EtlBatchMembershipTest {

    private final EtlBatchRepository batchRepository = mock(EtlBatchRepository.class);
    private final EtlTaskRepository taskRepository = mock(EtlTaskRepository.class);
    private final DatabaseConfigService databaseConfigService = mock(DatabaseConfigService.class);
    private final EtlTaskService taskService = mock(EtlTaskService.class);
    private final EtlCheckpointStore checkpointStore = mock(EtlCheckpointStore.class);
    private final DataSourceManager dataSourceManager = mock(DataSourceManager.class);
    private final EtlExecutionGuard guard = new EtlExecutionGuard();
    private final ConnectionCatalogService catalogService = mock(ConnectionCatalogService.class);
    private final SqlDialectFactory dialectFactory = mock(SqlDialectFactory.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private EtlBatchService service;

    @BeforeEach
    void setUp() {
        service = new EtlBatchService(batchRepository, taskRepository, databaseConfigService,
                taskService, checkpointStore, dataSourceManager, guard, catalogService,
                dialectFactory, Runnable::run, events);
    }

    @Test
    void createsOneMemberPerSelectedTableWithDefaultSelectStar() {
        stubConnections();
        when(catalogService.listTables(1L))
                .thenReturn(new ConnectionCatalogService.Tables("s", List.of("a", "b", "c")));
        when(dialectFactory.forType(any(), any(), any())).thenReturn(new GenericSqlDialect());
        when(batchRepository.save(any())).thenAnswer(invocation -> {
            EtlBatch batch = invocation.getArgument(0);
            batch.setId(5L);
            return batch;
        });
        when(taskRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlBatch form = form();
        form.setTimeoutSeconds(30);

        int created = service.createWithTables(form, List.of("c", "a"));

        assertThat(created).isEqualTo(2);
        ArgumentCaptor<Iterable<EtlTask>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(taskRepository).saveAll(captor.capture());
        List<EtlTask> members = new ArrayList<>();
        captor.getValue().forEach(members::add);

        assertThat(members).extracting(EtlTask::getName).containsExactly("a", "c");
        assertThat(members).extracting(EtlTask::getBatchId).containsOnly(5L);
        assertThat(members).extracting(EtlTask::getSourceDbId).containsOnly(1L);
        assertThat(members).extracting(EtlTask::getTargetDbId).containsOnly(2L);
        assertThat(members).extracting(EtlTask::getSourceSql)
                .containsExactly("select * from \"s\".\"a\"", "select * from \"s\".\"c\"");
        assertThat(members).extracting(EtlTask::getTargetTable).containsExactly("a", "c");
        assertThat(members).extracting(EtlTask::getWriteMode).containsOnly(EtlWriteMode.OVERWRITE);
        assertThat(members).extracting(EtlTask::getEnabled).containsOnly(false);
        assertThat(members).extracting(EtlTask::getCronExpression).containsOnlyNulls();
        assertThat(members).extracting(EtlTask::getTimeoutSeconds).containsOnly(30);
        verify(events).publishEvent(any(EtlBatchChangedEvent.class));
    }

    @Test
    void anEmptySelectionIsRejected() {
        assertThatThrownBy(() -> service.createWithTables(form(), List.of()))
                .isInstanceOf(EtlBatchTableException.class)
                .hasMessage("error.etl.batch.tables.none");
        verify(batchRepository, never()).save(any());
    }

    @Test
    void unknownTablesAreRejectedAndNamed() {
        stubConnections();
        when(catalogService.listTables(1L))
                .thenReturn(new ConnectionCatalogService.Tables("s", List.of("a")));

        assertThatThrownBy(() -> service.createWithTables(form(), List.of("a", "z")))
                .isInstanceOf(EtlBatchTableException.class)
                .hasMessage("error.etl.batch.tables.unknown")
                .satisfies(error -> assertThat(((EtlBatchTableException) error).names())
                        .containsExactly("z"));
    }

    @Test
    void tableNamesThatCannotBecomeTargetIdentifiersAreRejectedAndNamed() {
        stubConnections();
        when(catalogService.listTables(1L)).thenReturn(
                new ConnectionCatalogService.Tables("s", List.of("a b", "ok", "a\"b")));

        assertThatThrownBy(() -> service.createWithTables(form(), List.of("a b", "a\"b")))
                .isInstanceOf(EtlBatchTableException.class)
                .hasMessage("error.etl.batch.tables.invalid")
                .satisfies(error -> assertThat(((EtlBatchTableException) error).names())
                        .containsExactly("a b", "a\"b"));
    }

    @Test
    void aNativeMongoSourceIsRejectedBeforeAnyMemberIsCreated() {
        stubConnections();
        when(catalogService.listTables(1L))
                .thenThrow(new IllegalArgumentException("error.mongodb.native.jdbcUnsupported"));

        assertThatThrownBy(() -> service.createWithTables(form(), List.of("a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.mongodb.native.jdbcUnsupported");
        verify(batchRepository, never()).save(any());
    }

    @Test
    void addTablesRefusesTablesThatAreAlreadyInTheBatch() {
        EtlBatch batch = batch(5L);
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
        stubConnections();
        when(catalogService.listTables(1L))
                .thenReturn(new ConnectionCatalogService.Tables("s", List.of("a", "b")));
        EtlTask existing = member(7L, 5L, "a");
        when(taskRepository.findByBatchIdOrderByIdAsc(5L)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.addTables(5L, List.of("a", "b")))
                .isInstanceOf(EtlBatchTableException.class)
                .hasMessage("error.etl.batch.tables.exists")
                .satisfies(error -> assertThat(((EtlBatchTableException) error).names())
                        .containsExactly("a"));
        verify(taskRepository, never()).saveAll(any());
    }

    @Test
    void removingAMemberClearsItsCheckpointAndDeletesTheTask() {
        EtlBatch batch = batch(5L);
        EtlTask member = member(7L, 5L, "a");
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
        when(taskRepository.findById(7L)).thenReturn(Optional.of(member));
        when(taskRepository.findByBatchIdOrderByIdAsc(5L)).thenReturn(List.of(member));

        service.removeMember(5L, 7L);

        verify(checkpointStore).clear(7L);
        verify(taskRepository).delete(member);
        verify(events).publishEvent(any(EtlTaskChangedEvent.class));
    }

    @Test
    void removingTheLastMemberDisablesTheBatch() {
        EtlBatch batch = batch(5L);
        batch.setEnabled(true);
        EtlTask member = member(7L, 5L, "a");
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
        when(taskRepository.findById(7L)).thenReturn(Optional.of(member));
        when(taskRepository.findByBatchIdOrderByIdAsc(5L)).thenReturn(List.of());

        service.removeMember(5L, 7L);

        assertThat(batch.getEnabled()).isFalse();
        verify(batchRepository).save(batch);
    }

    @Test
    void removingIsRefusedWhileTheMemberRuns() {
        EtlBatch batch = batch(5L);
        EtlTask member = member(7L, 5L, "a");
        when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
        when(taskRepository.findById(7L)).thenReturn(Optional.of(member));
        guard.tryAcquire(7L);

        assertThatThrownBy(() -> service.removeMember(5L, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.batch.memberRunning");
        verify(taskRepository, never()).delete(any());
    }

    private void stubConnections() {
        when(databaseConfigService.findById(1L))
                .thenReturn(Optional.of(config(1L)));
        when(databaseConfigService.findById(2L))
                .thenReturn(Optional.of(config(2L)));
    }

    private static DatabaseConfig config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MYSQL);
        return config;
    }

    private static EtlBatch form() {
        EtlBatch batch = new EtlBatch();
        batch.setName("nightly");
        batch.setSourceDbId(1L);
        batch.setTargetDbId(2L);
        batch.setCronExpression("0 0 3 * * *");
        return batch;
    }

    private static EtlBatch batch(Long id) {
        EtlBatch batch = form();
        batch.setId(id);
        return batch;
    }

    private static EtlTask member(Long id, Long batchId, String targetTable) {
        EtlTask task = new EtlTask();
        task.setId(id);
        task.setBatchId(batchId);
        task.setTargetTable(targetTable);
        return task;
    }
}
