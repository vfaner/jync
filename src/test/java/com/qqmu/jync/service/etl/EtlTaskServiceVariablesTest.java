package com.qqmu.jync.service.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.qqmu.jync.service.auth.AccessGuard;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.EtlTask;
import com.qqmu.jync.model.EtlWriteMode;
import com.qqmu.jync.repository.EtlRunRepository;
import com.qqmu.jync.repository.EtlTaskRepository;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DataSourceManager;

/**
 * The variables box is part of the extraction configuration: changing it changes what the
 * query asks for, so it clears the checkpoint and cannot be edited under a running task.
 */
@ExtendWith(MockitoExtension.class)
class EtlTaskServiceVariablesTest {

    @Mock
    private EtlTaskRepository taskRepository;
    @Mock
    private EtlRunRepository runRepository;
    @Mock
    private DatabaseConfigService databaseConfigService;
    @Mock
    private EtlRunner runner;
    @Mock
    private EtlCheckpointStore checkpointStore;
    @Mock
    private DataSourceManager dataSourceManager;
    @Mock
    private ApplicationEventPublisher events;

    private EtlExecutionGuard guard;
    private EtlTaskService service;

    @BeforeEach
    void setUp() {
        guard = new EtlExecutionGuard();
        service = new EtlTaskService(taskRepository, runRepository, databaseConfigService, runner,
                checkpointStore, dataSourceManager, guard, Runnable::run, events);
    }

    @Test
    void saveStoresTheVariablesTrimmed() {
        stubConnections();
        captureSaved();

        service.save(form("  tenant='acme'  "), AccessGuard.system());

        assertThat(captured.getVariables()).isEqualTo("tenant='acme'");
    }

    @Test
    void aBlankVariablesBoxIsStoredAsAbsent() {
        stubConnections();
        captureSaved();

        EtlTask blank = form("   \n  ");
        blank.setSourceSql("SELECT id FROM src");
        service.save(blank, AccessGuard.system());

        assertThat(captured.getVariables()).isNull();
    }

    @Test
    void changingTheVariablesClearsTheCheckpoint() {
        stubConnections();
        EtlTask existing = existing(5L, "tenant='acme'");
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlTask edit = form("tenant='other'");
        edit.setId(5L);
        service.save(edit, AccessGuard.system());

        verify(checkpointStore).clear(5L);
    }

    @Test
    void rewritingTheSameVariablesKeepsTheCheckpoint() {
        stubConnections();
        EtlTask existing = existing(5L, "tenant='acme'");
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EtlTask edit = form("  tenant='acme'  ");
        edit.setId(5L);
        service.save(edit, AccessGuard.system());

        verify(checkpointStore, never()).clear(eq(5L));
    }

    /**
     * The counter answers "how many runs has this query had". A new extraction is a new
     * question, so its first run must read 0 — otherwise ${runIndex} would start wherever the
     * old query stopped. A rewrite of the same configuration is not a new question.
     */
    @Test
    void changingTheExtractionRestartsTheRunSeries() {
        stubConnections();
        captureSaved();
        EtlTask existing = existing(5L, "tenant='acme'");
        existing.setRunIndex(7);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));

        EtlTask edit = form("tenant='other'");
        edit.setId(5L);
        service.save(edit, AccessGuard.system());

        assertThat(captured.getRunIndex()).isZero();
    }

    @Test
    void rewritingTheSameExtractionKeepsTheRunSeries() {
        stubConnections();
        captureSaved();
        EtlTask existing = existing(5L, "tenant='acme'");
        existing.setRunIndex(7);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));

        EtlTask edit = form("  tenant='acme'  ");
        edit.setId(5L);
        service.save(edit, AccessGuard.system());

        assertThat(captured.getRunIndex()).isEqualTo(7);
    }

    @Test
    void changingTheVariablesOfARunningTaskIsRefused() {
        stubConnections();
        EtlTask existing = existing(5L, "tenant='acme'");
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        guard.tryAcquire(5L);

        EtlTask edit = form("tenant='other'");
        edit.setId(5L);

        assertThatThrownBy(() -> service.save(edit, AccessGuard.system()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error.etl.running.extraction");
        verify(taskRepository, never()).save(any());
        guard.release(5L);
    }

    @Test
    void anUnknownReferenceIsRefusedAndNothingIsSaved() {
        stubConnections();
        EtlTask task = form("tenant='acme'");
        task.setSourceSql("SELECT * FROM src WHERE tenant = ${nope}");

        assertThatThrownBy(() -> service.save(task, AccessGuard.system()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.var.unknown:nope");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void anInvalidDefinitionIsRefusedAndNothingIsSaved() {
        stubConnections();
        EtlTask task = form("tenant");

        assertThatThrownBy(() -> service.save(task, AccessGuard.system()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.etl.var.line:1");
        verify(taskRepository, never()).save(any());
    }

    // --- helpers ------------------------------------------------------------------------

    private EtlTask captured;

    /** Captures what the service would persist, so the assertions read the saved entity. */
    private void captureSaved() {
        when(taskRepository.save(any())).thenAnswer(invocation -> {
            captured = invocation.getArgument(0);
            return captured;
        });
    }

    private void stubConnections() {
        DatabaseConfig source = config(1L);
        DatabaseConfig target = config(2L);
        when(databaseConfigService.findById(1L)).thenReturn(Optional.of(source));
        when(databaseConfigService.findById(2L)).thenReturn(Optional.of(target));
    }

    private static DatabaseConfig config(Long id) {
        DatabaseConfig config = new DatabaseConfig();
        config.setId(id);
        config.setType(DatabaseType.MYSQL);
        return config;
    }

    private static EtlTask form(String variables) {
        EtlTask task = new EtlTask();
        task.setName("nightly orders");
        task.setSourceDbId(1L);
        task.setTargetDbId(2L);
        task.setSourceSql("SELECT id FROM src WHERE tenant = ${tenant}");
        task.setTargetTable("dst");
        task.setWriteMode(EtlWriteMode.OVERWRITE);
        task.setVariables(variables);
        return task;
    }

    private static EtlTask existing(Long id, String variables) {
        EtlTask task = form(variables);
        task.setId(id);
        return task;
    }
}
