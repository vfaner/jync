package com.qqmu.jync.service.etl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.qqmu.jync.model.EtlCheckpoint;
import com.qqmu.jync.repository.EtlCheckpointRepository;

/** JPA-backed {@link EtlCheckpointStore}. */
@Service
public class EtlCheckpointService implements EtlCheckpointStore {

    private final EtlCheckpointRepository repository;

    public EtlCheckpointService(EtlCheckpointRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public EtlCheckpointValue load(Long taskId) {
        return repository.findByTaskId(taskId)
                .map(marker -> new EtlCheckpointValue(marker.getKeyType(), marker.getKeyValue()))
                .orElse(null);
    }

    @Override
    @Transactional
    public void save(Long taskId, int jdbcType, String value) {
        EtlCheckpoint marker = repository.findByTaskId(taskId).orElseGet(() -> {
            EtlCheckpoint fresh = new EtlCheckpoint();
            fresh.setTaskId(taskId);
            return fresh;
        });
        marker.setKeyType(jdbcType);
        marker.setKeyValue(value);
        repository.save(marker);
    }

    @Override
    @Transactional
    public void clear(Long taskId) {
        repository.deleteByTaskId(taskId);
    }
}
