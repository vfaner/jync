package com.qqmu.jync.service.sync;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.qqmu.jync.model.ConstraintState;
import com.qqmu.jync.repository.ConstraintStateRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Persists "constraints disabled for these target tables" before a SQL Server data phase.
 * The marker is removed only after every table's constraints were restored; leaving it is
 * what lets the startup healer repair a process that was killed mid-cycle.
 */
@Slf4j
@Service
public class ConstraintStateStore {

    private final ConstraintStateRepository repository;

    public ConstraintStateStore(ConstraintStateRepository repository) {
        this.repository = repository;
    }

    public void begin(Long projectId, Long targetDbId, String schemaName,
                      List<String> tables) {
        if (projectId == null || targetDbId == null || tables == null || tables.isEmpty()) {
            return;
        }
        ConstraintState state = repository.findById(projectId).orElseGet(() -> {
            ConstraintState created = new ConstraintState();
            created.setProjectId(projectId);
            return created;
        });
        state.setTargetDbId(targetDbId);
        state.setSchemaName(schemaName);
        state.setTables(String.join("\n", tables));
        state.setUpdatedAt(Instant.now());
        repository.save(state);
    }

    public void clear(Long projectId) {
        if (projectId != null) {
            repository.deleteById(projectId);
        }
    }

    public List<ConstraintState> findAll() {
        return repository.findAll();
    }

    public void clear(ConstraintState state) {
        if (state != null && state.getProjectId() != null) {
            repository.deleteById(state.getProjectId());
        }
    }

    /**
     * Deletes the marker only if the row still has the timestamp the healer read. A live
     * cycle may have rewritten the marker while the restore was running; deleting that new
     * marker would leave constraints off with no record after a crash.
     */
    public void clearIfUnchanged(ConstraintState state) {
        if (state == null || state.getProjectId() == null) {
            return;
        }
        repository.findById(state.getProjectId())
                .filter(current -> java.util.Objects.equals(
                        current.getUpdatedAt(), state.getUpdatedAt()))
                .ifPresent(current -> repository.deleteById(state.getProjectId()));
    }
}
