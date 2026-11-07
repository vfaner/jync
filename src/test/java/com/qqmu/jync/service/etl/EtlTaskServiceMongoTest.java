package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * Saving a Mongo task must store what the source actually reads — the collection and the
 * filter — and must never carry stale Mongo fields into a SQL task. A change to either field
 * invalidates the resume position exactly like a SQL edit does.
 */
class EtlTaskServiceMongoTest {

    private EtlTaskRepository taskRepository;
    private EtlRunRepository runRepository;
    private DatabaseConfigService configs;
    private EtlCheckpointStore checkpointStore;
    private EtlTaskService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(EtlTaskRepository.class);
        runRepository = mock(EtlRunRepository.class);
        configs = mock(DatabaseConfigService.class);
        checkpointStore = mock(EtlCheckpointStore.class);
        service = new EtlTaskService(taskRepository, runRepository, configs,
                mock(EtlRunner.class), checkpointStore, mock(DataSourceManager.class),
                new EtlExecutionGuard(), Runnable::run, mock(ApplicationEventPublisher.class));
    }

    @Test
    void aMongoTaskStoresAnEmptySqlAndItsCollection() {
        stubSource(mongoSource(), h2Target());
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        EtlTask form = form();
        form.setSourceSql(null);
        form.setSourceCollection(" orders ");
        form.setSourceFilter(" {\"status\":\"active\"} ");

        EtlTask saved = service.save(form);

        assertThat(saved.getSourceSql()).isEmpty();
        assertThat(saved.getSourceCollection()).isEqualTo("orders");
        assertThat(saved.getSourceFilter()).isEqualTo("{\"status\":\"active\"}");
    }

    @Test
    void aSqlTaskDropsStaleMongoFields() {
        stubSource(h2Source(), h2Target());
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        EtlTask form = form();
        form.setSourceSql("SELECT id FROM src");
        form.setSourceCollection("orders");
        form.setSourceFilter("{\"status\":\"active\"}");

        EtlTask saved = service.save(form);

        assertThat(saved.getSourceCollection()).isNull();
        assertThat(saved.getSourceFilter()).isNull();
        assertThat(saved.getSourceSql()).isEqualTo("SELECT id FROM src");
    }

    @Test
    void changingTheCollectionClearsTheCheckpoint() {
        stubSource(mongoSource(), h2Target());
        EtlTask existing = existing("orders", null);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        EtlTask edit = form();
        edit.setId(5L);
        edit.setSourceCollection("orders2");

        service.save(edit);

        verify(checkpointStore).clear(5L);
    }

    @Test
    void changingTheFilterClearsTheCheckpoint() {
        stubSource(mongoSource(), h2Target());
        EtlTask existing = existing("orders", "{\"status\":\"active\"}");
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        EtlTask edit = form();
        edit.setId(5L);
        edit.setSourceFilter("{\"status\":\"paid\"}");

        service.save(edit);

        verify(checkpointStore).clear(5L);
    }

    @Test
    void renamingAMongoTaskKeepsTheCheckpoint() {
        stubSource(mongoSource(), h2Target());
        EtlTask existing = existing("orders", null);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        EtlTask edit = form();
        edit.setId(5L);
        edit.setName("renamed");

        service.save(edit);

        verify(checkpointStore, never()).clear(eq(5L));
    }

    @Test
    void saveRejectsAMongoTaskWithoutACollection() {
        stubSource(mongoSource(), h2Target());
        EtlTask form = form();
        form.setSourceCollection(" ");

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.collection.required");
        verify(taskRepository, never()).save(any());
    }

    // --- helpers ------------------------------------------------------------------------

    private void stubSource(DatabaseConfig source, DatabaseConfig target) {
        when(configs.findById(1L)).thenReturn(Optional.of(source));
        when(configs.findById(2L)).thenReturn(Optional.of(target));
    }

    private static EtlTask form() {
        EtlTask task = new EtlTask();
        task.setName("nightly orders");
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setSourceCollection("orders");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        return task;
    }

    private static EtlTask existing(String collection, String filter) {
        EtlTask task = form();
        task.setId(5L);
        task.setSourceCollection(collection);
        task.setSourceFilter(filter);
        return task;
    }

    private static DatabaseConfig mongoSource() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(1L);
        config.setType(DatabaseType.MONGODB_NATIVE);
        return config;
    }

    private static DatabaseConfig h2Source() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(1L);
        config.setType(DatabaseType.H2);
        return config;
    }

    private static DatabaseConfig h2Target() {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(2L);
        config.setType(DatabaseType.H2);
        return config;
    }
}
