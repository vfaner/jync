package com.qqmu.jync.service.fanout;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.FanoutProgress;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.repository.FanoutProgressRepository;

/**
 * Per-target progress rows for a fan-out project. Kept separate from SyncStateWriter so the
 * single-target path cannot accidentally read a target's row as its own.
 */
@Service
public class FanoutProgressStore {

    private final FanoutProgressRepository repository;

    public FanoutProgressStore(FanoutProgressRepository repository) {
        this.repository = repository;
    }

    public Optional<FanoutProgress> find(Long projectId, Long targetDbId, String table) {
        return repository.findByProjectIdAndTargetDbIdAndObjectTypeAndObjectName(
                projectId, targetDbId, ObjectType.TABLE, table);
    }

    public List<FanoutProgress> list(Long projectId, Long targetDbId) {
        return repository.findByProjectIdAndTargetDbId(projectId, targetDbId);
    }

    @Transactional
    public FanoutProgress loadOrCreate(Long projectId, Long targetDbId, String table) {
        return find(projectId, targetDbId, table).orElseGet(() -> {
            FanoutProgress created = new FanoutProgress();
            created.setProjectId(projectId);
            created.setTargetDbId(targetDbId);
            created.setObjectName(table);
            return repository.save(created);
        });
    }

    @Transactional
    public void save(FanoutProgress progress) {
        repository.save(progress);
    }

    /**
     * A rebaseline starts every target from scratch. Leaving fan-out progress behind makes
     * the detail page keep saying COMPLETED (targetsDone == targetsTotal) while the baseline
     * is actually rewriting the targets.
     */
    @Transactional
    public void deleteByProjectId(Long projectId) {
        repository.deleteByProjectId(projectId);
    }
}
